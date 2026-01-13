-- ============================================
-- ASIS Oracle 초기화 스크립트
-- 04. WORKER 프로시저 생성
-- ============================================

-- 해시 생성 함수
CREATE OR REPLACE FUNCTION FN_GENERATE_HASH(
    p_table_name  VARCHAR2,
    p_pk_value    VARCHAR2,
    p_operation   VARCHAR2,
    p_data        VARCHAR2
) RETURN VARCHAR2
IS
BEGIN
    RETURN RAWTOHEX(
        DBMS_CRYPTO.HASH(
            UTL_RAW.CAST_TO_RAW(p_table_name || '|' || p_pk_value || '|' || p_operation || '|' || p_data),
            DBMS_CRYPTO.HASH_SH256
        )
    );
END;
/

-- 무한루프 체크 함수
CREATE OR REPLACE FUNCTION FN_IS_LOOP(
    p_hash VARCHAR2
) RETURN BOOLEAN
IS
    v_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_count
    FROM CDC_PROCESSED_HASH
    WHERE HASH_VALUE = p_hash
      AND PROCESSED_AT > SYSTIMESTAMP - INTERVAL '5' MINUTE;  -- 5분 이내 처리된 것

    RETURN v_count > 0;
END;
/

-- 해시 기록 프로시저
CREATE OR REPLACE PROCEDURE SP_RECORD_HASH(
    p_hash        VARCHAR2,
    p_table_name  VARCHAR2,
    p_pk_value    VARCHAR2
)
IS
BEGIN
    MERGE INTO CDC_PROCESSED_HASH t
    USING (SELECT p_hash AS hash_value FROM DUAL) s
    ON (t.HASH_VALUE = s.hash_value)
    WHEN MATCHED THEN
        UPDATE SET PROCESSED_AT = SYSTIMESTAMP
    WHEN NOT MATCHED THEN
        INSERT (HASH_VALUE, TABLE_NAME, PK_VALUE, PROCESSED_AT)
        VALUES (p_hash, p_table_name, p_pk_value, SYSTIMESTAMP);
    COMMIT;
END;
/

-- 오래된 해시 정리 프로시저
CREATE OR REPLACE PROCEDURE SP_CLEANUP_HASH
IS
BEGIN
    DELETE FROM CDC_PROCESSED_HASH
    WHERE PROCESSED_AT < SYSTIMESTAMP - INTERVAL '10' MINUTE;
    COMMIT;
END;
/

-- ============================================
-- BOOK 테이블 WORKER
-- ============================================
CREATE OR REPLACE PROCEDURE SP_WORKER_BOOK
IS
    v_hash VARCHAR2(64);
    v_is_loop BOOLEAN;
BEGIN
    -- 1단계: CDC → STAGING (스키마 변환)
    FOR rec IN (
        SELECT CDC_SEQ, OPERATION, BOOK_ID, TITLE, AUTHOR_NAME,
               CATEGORY_CD, IS_ACTIVE, SOURCE_TIMESTAMP, CHANGE_HASH
        FROM CDC_ASIS_BOOK
        WHERE PROCESSED_YN = 'N'
        ORDER BY CDC_SEQ
    ) LOOP
        BEGIN
            -- 무한루프 체크
            IF FN_IS_LOOP(rec.CHANGE_HASH) THEN
                -- 루프 감지 - 건너뛰기
                UPDATE CDC_ASIS_BOOK
                SET PROCESSED_YN = 'S',  -- S = Skipped
                    PROCESSED_AT = SYSTIMESTAMP,
                    ERROR_MSG = 'LOOP_BLOCKED'
                WHERE CDC_SEQ = rec.CDC_SEQ;

                INSERT INTO CDC_SYNC_LOG (DIRECTION, TABLE_NAME, OPERATION, PK_VALUE, STATUS, CHANGE_HASH)
                VALUES ('TOBE_TO_ASIS', 'BOOK_INFO', rec.OPERATION, TO_CHAR(rec.BOOK_ID), 'LOOP_BLOCKED', rec.CHANGE_HASH);
            ELSE
                -- STAGING에 삽입 (스키마 변환 적용)
                INSERT INTO STAGING_ASIS_BOOK (
                    CDC_SEQ, OPERATION, BOOK_ID, BOOK_TITLE, AUTHOR,
                    CATEGORY, STATUS, REG_DATE, MOD_DATE
                ) VALUES (
                    rec.CDC_SEQ,
                    rec.OPERATION,
                    rec.BOOK_ID,
                    rec.TITLE,  -- TOBE.TITLE → ASIS.BOOK_TITLE
                    rec.AUTHOR_NAME,  -- TOBE.AUTHOR_NAME → ASIS.AUTHOR
                    FN_CONVERT_CODE('CATEGORY_MAP', 'TOBE', rec.CATEGORY_CD),  -- LIT→01
                    FN_CONVERT_CODE('STATUS_MAP', 'TOBE', TO_CHAR(rec.IS_ACTIVE)),  -- 1→Y
                    SYSDATE,
                    SYSDATE
                );

                UPDATE CDC_ASIS_BOOK
                SET PROCESSED_YN = 'Y',
                    PROCESSED_AT = SYSTIMESTAMP
                WHERE CDC_SEQ = rec.CDC_SEQ;
            END IF;

            COMMIT;
        EXCEPTION
            WHEN OTHERS THEN
                UPDATE CDC_ASIS_BOOK
                SET PROCESSED_YN = 'E',
                    PROCESSED_AT = SYSTIMESTAMP,
                    ERROR_MSG = SQLERRM
                WHERE CDC_SEQ = rec.CDC_SEQ;
                COMMIT;
        END;
    END LOOP;

    -- 2단계: STAGING → 원본 테이블 반영
    FOR rec IN (
        SELECT STAGING_SEQ, CDC_SEQ, OPERATION, BOOK_ID, BOOK_TITLE,
               AUTHOR, CATEGORY, STATUS, REG_DATE, MOD_DATE
        FROM STAGING_ASIS_BOOK
        WHERE PROCESSED_YN = 'N'
        ORDER BY STAGING_SEQ
    ) LOOP
        BEGIN
            CASE rec.OPERATION
                WHEN 'INSERT' THEN
                    INSERT INTO BOOK_INFO (BOOK_ID, BOOK_TITLE, AUTHOR, CATEGORY, STATUS, REG_DATE, MOD_DATE)
                    VALUES (rec.BOOK_ID, rec.BOOK_TITLE, rec.AUTHOR, rec.CATEGORY, rec.STATUS, rec.REG_DATE, rec.MOD_DATE);

                WHEN 'UPDATE' THEN
                    UPDATE BOOK_INFO
                    SET BOOK_TITLE = rec.BOOK_TITLE,
                        AUTHOR = rec.AUTHOR,
                        CATEGORY = rec.CATEGORY,
                        STATUS = rec.STATUS,
                        MOD_DATE = rec.MOD_DATE
                    WHERE BOOK_ID = rec.BOOK_ID;

                WHEN 'DELETE' THEN
                    DELETE FROM BOOK_INFO WHERE BOOK_ID = rec.BOOK_ID;
            END CASE;

            UPDATE STAGING_ASIS_BOOK
            SET PROCESSED_YN = 'Y',
                PROCESSED_AT = SYSTIMESTAMP
            WHERE STAGING_SEQ = rec.STAGING_SEQ;

            -- 해시 기록 (무한루프 방지)
            v_hash := FN_GENERATE_HASH('BOOK_INFO', TO_CHAR(rec.BOOK_ID), rec.OPERATION,
                                       rec.BOOK_TITLE || rec.AUTHOR || rec.CATEGORY || rec.STATUS);
            SP_RECORD_HASH(v_hash, 'BOOK_INFO', TO_CHAR(rec.BOOK_ID));

            INSERT INTO CDC_SYNC_LOG (DIRECTION, TABLE_NAME, OPERATION, PK_VALUE, STATUS)
            VALUES ('TOBE_TO_ASIS', 'BOOK_INFO', rec.OPERATION, TO_CHAR(rec.BOOK_ID), 'SUCCESS');

            COMMIT;
        EXCEPTION
            WHEN DUP_VAL_ON_INDEX THEN
                -- INSERT 시 이미 존재하면 UPDATE로 변환
                UPDATE BOOK_INFO
                SET BOOK_TITLE = rec.BOOK_TITLE,
                    AUTHOR = rec.AUTHOR,
                    CATEGORY = rec.CATEGORY,
                    STATUS = rec.STATUS,
                    MOD_DATE = rec.MOD_DATE
                WHERE BOOK_ID = rec.BOOK_ID;

                UPDATE STAGING_ASIS_BOOK
                SET PROCESSED_YN = 'Y',
                    PROCESSED_AT = SYSTIMESTAMP
                WHERE STAGING_SEQ = rec.STAGING_SEQ;
                COMMIT;
            WHEN OTHERS THEN
                UPDATE STAGING_ASIS_BOOK
                SET PROCESSED_YN = 'E',
                    PROCESSED_AT = SYSTIMESTAMP,
                    ERROR_MSG = SQLERRM
                WHERE STAGING_SEQ = rec.STAGING_SEQ;

                INSERT INTO CDC_SYNC_LOG (DIRECTION, TABLE_NAME, OPERATION, PK_VALUE, STATUS, ERROR_MSG)
                VALUES ('TOBE_TO_ASIS', 'BOOK_INFO', rec.OPERATION, TO_CHAR(rec.BOOK_ID), 'FAILED', SQLERRM);
                COMMIT;
        END;
    END LOOP;
END;
/

-- ============================================
-- MEMBER 테이블 WORKER
-- ============================================
CREATE OR REPLACE PROCEDURE SP_WORKER_MEMBER
IS
    v_hash VARCHAR2(64);
BEGIN
    -- 1단계: CDC → STAGING (스키마 변환)
    FOR rec IN (
        SELECT CDC_SEQ, OPERATION, MEMBER_ID, MEMBER_NAME, EMAIL_ADDR,
               MEMBER_TYPE_CD, IS_ACTIVE, SOURCE_TIMESTAMP, CHANGE_HASH
        FROM CDC_ASIS_MEMBER
        WHERE PROCESSED_YN = 'N'
        ORDER BY CDC_SEQ
    ) LOOP
        BEGIN
            IF FN_IS_LOOP(rec.CHANGE_HASH) THEN
                UPDATE CDC_ASIS_MEMBER
                SET PROCESSED_YN = 'S', PROCESSED_AT = SYSTIMESTAMP, ERROR_MSG = 'LOOP_BLOCKED'
                WHERE CDC_SEQ = rec.CDC_SEQ;
            ELSE
                INSERT INTO STAGING_ASIS_MEMBER (
                    CDC_SEQ, OPERATION, MEMBER_ID, MEMBER_NAME, EMAIL, MEMBER_TYPE, STATUS, REG_DATE
                ) VALUES (
                    rec.CDC_SEQ,
                    rec.OPERATION,
                    rec.MEMBER_ID,
                    rec.MEMBER_NAME,
                    rec.EMAIL_ADDR,
                    FN_CONVERT_CODE('MEMBER_TYPE_MAP', 'TOBE', rec.MEMBER_TYPE_CD),
                    FN_CONVERT_CODE('STATUS_MAP', 'TOBE', TO_CHAR(rec.IS_ACTIVE)),
                    SYSDATE
                );
                UPDATE CDC_ASIS_MEMBER SET PROCESSED_YN = 'Y', PROCESSED_AT = SYSTIMESTAMP WHERE CDC_SEQ = rec.CDC_SEQ;
            END IF;
            COMMIT;
        EXCEPTION
            WHEN OTHERS THEN
                UPDATE CDC_ASIS_MEMBER SET PROCESSED_YN = 'E', PROCESSED_AT = SYSTIMESTAMP, ERROR_MSG = SQLERRM WHERE CDC_SEQ = rec.CDC_SEQ;
                COMMIT;
        END;
    END LOOP;

    -- 2단계: STAGING → 원본
    FOR rec IN (
        SELECT * FROM STAGING_ASIS_MEMBER WHERE PROCESSED_YN = 'N' ORDER BY STAGING_SEQ
    ) LOOP
        BEGIN
            CASE rec.OPERATION
                WHEN 'INSERT' THEN
                    INSERT INTO MEMBER_INFO (MEMBER_ID, MEMBER_NAME, EMAIL, MEMBER_TYPE, STATUS, REG_DATE)
                    VALUES (rec.MEMBER_ID, rec.MEMBER_NAME, rec.EMAIL, rec.MEMBER_TYPE, rec.STATUS, rec.REG_DATE);
                WHEN 'UPDATE' THEN
                    UPDATE MEMBER_INFO SET MEMBER_NAME = rec.MEMBER_NAME, EMAIL = rec.EMAIL,
                           MEMBER_TYPE = rec.MEMBER_TYPE, STATUS = rec.STATUS WHERE MEMBER_ID = rec.MEMBER_ID;
                WHEN 'DELETE' THEN
                    DELETE FROM MEMBER_INFO WHERE MEMBER_ID = rec.MEMBER_ID;
            END CASE;
            UPDATE STAGING_ASIS_MEMBER SET PROCESSED_YN = 'Y', PROCESSED_AT = SYSTIMESTAMP WHERE STAGING_SEQ = rec.STAGING_SEQ;
            v_hash := FN_GENERATE_HASH('MEMBER_INFO', TO_CHAR(rec.MEMBER_ID), rec.OPERATION, rec.MEMBER_NAME || rec.EMAIL);
            SP_RECORD_HASH(v_hash, 'MEMBER_INFO', TO_CHAR(rec.MEMBER_ID));
            COMMIT;
        EXCEPTION
            WHEN DUP_VAL_ON_INDEX THEN
                UPDATE MEMBER_INFO SET MEMBER_NAME = rec.MEMBER_NAME, EMAIL = rec.EMAIL WHERE MEMBER_ID = rec.MEMBER_ID;
                UPDATE STAGING_ASIS_MEMBER SET PROCESSED_YN = 'Y', PROCESSED_AT = SYSTIMESTAMP WHERE STAGING_SEQ = rec.STAGING_SEQ;
                COMMIT;
            WHEN OTHERS THEN
                UPDATE STAGING_ASIS_MEMBER SET PROCESSED_YN = 'E', ERROR_MSG = SQLERRM WHERE STAGING_SEQ = rec.STAGING_SEQ;
                COMMIT;
        END;
    END LOOP;
END;
/

-- ============================================
-- NEW_SERVICE 테이블 WORKER (단방향: TOBE→ASIS)
-- ============================================
CREATE OR REPLACE PROCEDURE SP_WORKER_NEW_SERVICE
IS
BEGIN
    -- 1단계: CDC → STAGING
    FOR rec IN (
        SELECT * FROM CDC_ASIS_NEW_SERVICE WHERE PROCESSED_YN = 'N' ORDER BY CDC_SEQ
    ) LOOP
        BEGIN
            IF FN_IS_LOOP(rec.CHANGE_HASH) THEN
                UPDATE CDC_ASIS_NEW_SERVICE SET PROCESSED_YN = 'S', ERROR_MSG = 'LOOP_BLOCKED' WHERE CDC_SEQ = rec.CDC_SEQ;
            ELSE
                INSERT INTO STAGING_ASIS_NEW_SERVICE (
                    CDC_SEQ, OPERATION, SERVICE_ID, SERVICE_NM, SVC_TYPE, USE_YN, REG_DATE
                ) VALUES (
                    rec.CDC_SEQ, rec.OPERATION, rec.SERVICE_ID, rec.SERVICE_NAME,
                    rec.SERVICE_TYPE_CD, FN_CONVERT_CODE('STATUS_MAP', 'TOBE', TO_CHAR(rec.IS_ACTIVE)), SYSDATE
                );
                UPDATE CDC_ASIS_NEW_SERVICE SET PROCESSED_YN = 'Y', PROCESSED_AT = SYSTIMESTAMP WHERE CDC_SEQ = rec.CDC_SEQ;
            END IF;
            COMMIT;
        EXCEPTION
            WHEN OTHERS THEN
                UPDATE CDC_ASIS_NEW_SERVICE SET PROCESSED_YN = 'E', ERROR_MSG = SQLERRM WHERE CDC_SEQ = rec.CDC_SEQ;
                COMMIT;
        END;
    END LOOP;

    -- 2단계: STAGING → 원본
    FOR rec IN (
        SELECT * FROM STAGING_ASIS_NEW_SERVICE WHERE PROCESSED_YN = 'N' ORDER BY STAGING_SEQ
    ) LOOP
        BEGIN
            CASE rec.OPERATION
                WHEN 'INSERT' THEN
                    INSERT INTO NEW_SERVICE_RECV VALUES (rec.SERVICE_ID, rec.SERVICE_NM, rec.SVC_TYPE, rec.USE_YN, rec.REG_DATE);
                WHEN 'UPDATE' THEN
                    UPDATE NEW_SERVICE_RECV SET SERVICE_NM = rec.SERVICE_NM, SVC_TYPE = rec.SVC_TYPE, USE_YN = rec.USE_YN WHERE SERVICE_ID = rec.SERVICE_ID;
                WHEN 'DELETE' THEN
                    DELETE FROM NEW_SERVICE_RECV WHERE SERVICE_ID = rec.SERVICE_ID;
            END CASE;
            UPDATE STAGING_ASIS_NEW_SERVICE SET PROCESSED_YN = 'Y', PROCESSED_AT = SYSTIMESTAMP WHERE STAGING_SEQ = rec.STAGING_SEQ;
            COMMIT;
        EXCEPTION
            WHEN DUP_VAL_ON_INDEX THEN
                UPDATE NEW_SERVICE_RECV SET SERVICE_NM = rec.SERVICE_NM WHERE SERVICE_ID = rec.SERVICE_ID;
                UPDATE STAGING_ASIS_NEW_SERVICE SET PROCESSED_YN = 'Y' WHERE STAGING_SEQ = rec.STAGING_SEQ;
                COMMIT;
            WHEN OTHERS THEN
                UPDATE STAGING_ASIS_NEW_SERVICE SET PROCESSED_YN = 'E', ERROR_MSG = SQLERRM WHERE STAGING_SEQ = rec.STAGING_SEQ;
                COMMIT;
        END;
    END LOOP;
END;
/

-- 전체 WORKER 실행 프로시저
CREATE OR REPLACE PROCEDURE SP_RUN_ALL_WORKERS
IS
BEGIN
    SP_CLEANUP_HASH;
    SP_WORKER_BOOK;
    SP_WORKER_MEMBER;
    SP_WORKER_NEW_SERVICE;
END;
/
