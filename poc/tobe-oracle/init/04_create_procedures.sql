-- ============================================
-- TOBE Oracle 초기화 스크립트
-- 04. WORKER 프로시저 생성
-- ============================================

-- PDB로 전환
ALTER SESSION SET CONTAINER = XEPDB1;

-- TOBE_USER 스키마로 전환
ALTER SESSION SET CURRENT_SCHEMA = TOBE_USER;

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
      AND PROCESSED_AT > SYSTIMESTAMP - INTERVAL '5' MINUTE;

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

-- 오래된 해시 정리
CREATE OR REPLACE PROCEDURE SP_CLEANUP_HASH
IS
BEGIN
    DELETE FROM CDC_PROCESSED_HASH WHERE PROCESSED_AT < SYSTIMESTAMP - INTERVAL '10' MINUTE;
    COMMIT;
END;
/

-- ============================================
-- LEGACY_CODE 테이블 WORKER (단방향: ASIS→TOBE)
-- ============================================
CREATE OR REPLACE PROCEDURE SP_WORKER_LEGACY_CODE
IS
    v_err_msg VARCHAR2(500);
BEGIN
    -- 1단계: CDC → STAGING (스키마 변환)
    FOR rec IN (
        SELECT * FROM CDC_TOBE_LEGACY_CODE WHERE PROCESSED_YN = 'N' ORDER BY CDC_SEQ
    ) LOOP
        BEGIN
            IF FN_IS_LOOP(rec.CHANGE_HASH) THEN
                UPDATE CDC_TOBE_LEGACY_CODE SET PROCESSED_YN = 'S', ERROR_MSG = 'LOOP_BLOCKED' WHERE CDC_SEQ = rec.CDC_SEQ;
            ELSE
                INSERT INTO STAGING_TOBE_LEGACY_CODE (
                    CDC_SEQ, OPERATION, CODE_ID, CODE_NAME, IS_ACTIVE, CREATED_BY
                ) VALUES (
                    rec.CDC_SEQ, rec.OPERATION, rec.CODE_ID, rec.CODE_NAME,
                    CASE rec.USE_YN WHEN 'Y' THEN 1 ELSE 0 END,  -- Y→1, N→0
                    'SYNC'
                );
                UPDATE CDC_TOBE_LEGACY_CODE SET PROCESSED_YN = 'Y', PROCESSED_AT = SYSTIMESTAMP WHERE CDC_SEQ = rec.CDC_SEQ;
            END IF;
            COMMIT;
        EXCEPTION
            WHEN OTHERS THEN
                v_err_msg := SUBSTR(SQLERRM, 1, 500);
                UPDATE CDC_TOBE_LEGACY_CODE SET PROCESSED_YN = 'E', ERROR_MSG = v_err_msg WHERE CDC_SEQ = rec.CDC_SEQ;
                COMMIT;
        END;
    END LOOP;

    -- 2단계: STAGING → 원본
    FOR rec IN (
        SELECT * FROM STAGING_TOBE_LEGACY_CODE WHERE PROCESSED_YN = 'N' ORDER BY STAGING_SEQ
    ) LOOP
        BEGIN
            CASE rec.OPERATION
                WHEN 'INSERT' THEN
                    INSERT INTO TB_LEGACY_CODE (CODE_ID, CODE_NAME, IS_ACTIVE, CREATED_BY)
                    VALUES (rec.CODE_ID, rec.CODE_NAME, rec.IS_ACTIVE, rec.CREATED_BY);
                WHEN 'UPDATE' THEN
                    UPDATE TB_LEGACY_CODE SET CODE_NAME = rec.CODE_NAME, IS_ACTIVE = rec.IS_ACTIVE WHERE CODE_ID = rec.CODE_ID;
                WHEN 'DELETE' THEN
                    DELETE FROM TB_LEGACY_CODE WHERE CODE_ID = rec.CODE_ID;
            END CASE;
            UPDATE STAGING_TOBE_LEGACY_CODE SET PROCESSED_YN = 'Y', PROCESSED_AT = SYSTIMESTAMP WHERE STAGING_SEQ = rec.STAGING_SEQ;
            INSERT INTO CDC_SYNC_LOG (DIRECTION, TABLE_NAME, OPERATION, PK_VALUE, STATUS)
            VALUES ('ASIS_TO_TOBE', 'TB_LEGACY_CODE', rec.OPERATION, rec.CODE_ID, 'SUCCESS');
            COMMIT;
        EXCEPTION
            WHEN DUP_VAL_ON_INDEX THEN
                UPDATE TB_LEGACY_CODE SET CODE_NAME = rec.CODE_NAME WHERE CODE_ID = rec.CODE_ID;
                UPDATE STAGING_TOBE_LEGACY_CODE SET PROCESSED_YN = 'Y' WHERE STAGING_SEQ = rec.STAGING_SEQ;
                COMMIT;
            WHEN OTHERS THEN
                v_err_msg := SUBSTR(SQLERRM, 1, 500);
                UPDATE STAGING_TOBE_LEGACY_CODE SET PROCESSED_YN = 'E', ERROR_MSG = v_err_msg WHERE STAGING_SEQ = rec.STAGING_SEQ;
                COMMIT;
        END;
    END LOOP;
END;
/

-- ============================================
-- BOOK 테이블 WORKER (양방향)
-- ============================================
CREATE OR REPLACE PROCEDURE SP_WORKER_BOOK
IS
    v_hash VARCHAR2(64);
    v_err_msg VARCHAR2(500);
BEGIN
    -- 1단계: CDC → STAGING (스키마 변환: ASIS→TOBE)
    FOR rec IN (
        SELECT * FROM CDC_TOBE_BOOK WHERE PROCESSED_YN = 'N' ORDER BY CDC_SEQ
    ) LOOP
        BEGIN
            IF FN_IS_LOOP(rec.CHANGE_HASH) THEN
                UPDATE CDC_TOBE_BOOK SET PROCESSED_YN = 'S', ERROR_MSG = 'LOOP_BLOCKED' WHERE CDC_SEQ = rec.CDC_SEQ;
                INSERT INTO CDC_SYNC_LOG (DIRECTION, TABLE_NAME, OPERATION, PK_VALUE, STATUS, CHANGE_HASH)
                VALUES ('ASIS_TO_TOBE', 'TB_BOOK', rec.OPERATION, TO_CHAR(rec.BOOK_ID), 'LOOP_BLOCKED', rec.CHANGE_HASH);
            ELSE
                INSERT INTO STAGING_TOBE_BOOK (
                    CDC_SEQ, OPERATION, BOOK_ID, TITLE, AUTHOR_NAME,
                    CATEGORY_CD, IS_ACTIVE, CREATED_AT, UPDATED_AT
                ) VALUES (
                    rec.CDC_SEQ,
                    rec.OPERATION,
                    rec.BOOK_ID,
                    rec.BOOK_TITLE,  -- ASIS.BOOK_TITLE → TOBE.TITLE
                    rec.AUTHOR,      -- ASIS.AUTHOR → TOBE.AUTHOR_NAME
                    FN_CONVERT_CODE('CATEGORY_MAP', 'ASIS', rec.CATEGORY),  -- 01→LIT
                    CASE rec.STATUS WHEN 'Y' THEN 1 ELSE 0 END,  -- Y→1
                    NVL(CAST(rec.REG_DATE AS TIMESTAMP), SYSTIMESTAMP),
                    CAST(rec.MOD_DATE AS TIMESTAMP)
                );
                UPDATE CDC_TOBE_BOOK SET PROCESSED_YN = 'Y', PROCESSED_AT = SYSTIMESTAMP WHERE CDC_SEQ = rec.CDC_SEQ;
            END IF;
            COMMIT;
        EXCEPTION
            WHEN OTHERS THEN
                v_err_msg := SUBSTR(SQLERRM, 1, 500);
                UPDATE CDC_TOBE_BOOK SET PROCESSED_YN = 'E', ERROR_MSG = v_err_msg WHERE CDC_SEQ = rec.CDC_SEQ;
                COMMIT;
        END;
    END LOOP;

    -- 2단계: STAGING → 원본
    FOR rec IN (
        SELECT * FROM STAGING_TOBE_BOOK WHERE PROCESSED_YN = 'N' ORDER BY STAGING_SEQ
    ) LOOP
        BEGIN
            CASE rec.OPERATION
                WHEN 'INSERT' THEN
                    INSERT INTO TB_BOOK (BOOK_ID, TITLE, AUTHOR_NAME, CATEGORY_CD, IS_ACTIVE, CREATED_AT, UPDATED_AT)
                    VALUES (rec.BOOK_ID, rec.TITLE, rec.AUTHOR_NAME, rec.CATEGORY_CD, rec.IS_ACTIVE, rec.CREATED_AT, rec.UPDATED_AT);
                WHEN 'UPDATE' THEN
                    UPDATE TB_BOOK SET TITLE = rec.TITLE, AUTHOR_NAME = rec.AUTHOR_NAME, CATEGORY_CD = rec.CATEGORY_CD,
                           IS_ACTIVE = rec.IS_ACTIVE, UPDATED_AT = rec.UPDATED_AT WHERE BOOK_ID = rec.BOOK_ID;
                WHEN 'DELETE' THEN
                    DELETE FROM TB_BOOK WHERE BOOK_ID = rec.BOOK_ID;
            END CASE;
            UPDATE STAGING_TOBE_BOOK SET PROCESSED_YN = 'Y', PROCESSED_AT = SYSTIMESTAMP WHERE STAGING_SEQ = rec.STAGING_SEQ;

            -- 해시 기록 (무한루프 방지)
            v_hash := FN_GENERATE_HASH('TB_BOOK', TO_CHAR(rec.BOOK_ID), rec.OPERATION, rec.TITLE || rec.AUTHOR_NAME || rec.CATEGORY_CD);
            SP_RECORD_HASH(v_hash, 'TB_BOOK', TO_CHAR(rec.BOOK_ID));

            INSERT INTO CDC_SYNC_LOG (DIRECTION, TABLE_NAME, OPERATION, PK_VALUE, STATUS)
            VALUES ('ASIS_TO_TOBE', 'TB_BOOK', rec.OPERATION, TO_CHAR(rec.BOOK_ID), 'SUCCESS');
            COMMIT;
        EXCEPTION
            WHEN DUP_VAL_ON_INDEX THEN
                UPDATE TB_BOOK SET TITLE = rec.TITLE, AUTHOR_NAME = rec.AUTHOR_NAME WHERE BOOK_ID = rec.BOOK_ID;
                UPDATE STAGING_TOBE_BOOK SET PROCESSED_YN = 'Y' WHERE STAGING_SEQ = rec.STAGING_SEQ;
                COMMIT;
            WHEN OTHERS THEN
                v_err_msg := SUBSTR(SQLERRM, 1, 500);
                UPDATE STAGING_TOBE_BOOK SET PROCESSED_YN = 'E', ERROR_MSG = v_err_msg WHERE STAGING_SEQ = rec.STAGING_SEQ;
                INSERT INTO CDC_SYNC_LOG (DIRECTION, TABLE_NAME, OPERATION, PK_VALUE, STATUS, ERROR_MSG)
                VALUES ('ASIS_TO_TOBE', 'TB_BOOK', rec.OPERATION, TO_CHAR(rec.BOOK_ID), 'FAILED', v_err_msg);
                COMMIT;
        END;
    END LOOP;
END;
/

-- ============================================
-- MEMBER 테이블 WORKER (양방향)
-- ============================================
CREATE OR REPLACE PROCEDURE SP_WORKER_MEMBER
IS
    v_hash VARCHAR2(64);
    v_err_msg VARCHAR2(500);
BEGIN
    -- 1단계: CDC → STAGING
    FOR rec IN (
        SELECT * FROM CDC_TOBE_MEMBER WHERE PROCESSED_YN = 'N' ORDER BY CDC_SEQ
    ) LOOP
        BEGIN
            IF FN_IS_LOOP(rec.CHANGE_HASH) THEN
                UPDATE CDC_TOBE_MEMBER SET PROCESSED_YN = 'S', ERROR_MSG = 'LOOP_BLOCKED' WHERE CDC_SEQ = rec.CDC_SEQ;
            ELSE
                INSERT INTO STAGING_TOBE_MEMBER (
                    CDC_SEQ, OPERATION, MEMBER_ID, MEMBER_NAME, EMAIL_ADDR, MEMBER_TYPE_CD, IS_ACTIVE, CREATED_AT
                ) VALUES (
                    rec.CDC_SEQ, rec.OPERATION, rec.MEMBER_ID, rec.MEMBER_NAME,
                    rec.EMAIL,  -- ASIS.EMAIL → TOBE.EMAIL_ADDR
                    FN_CONVERT_CODE('MEMBER_TYPE_MAP', 'ASIS', rec.MEMBER_TYPE),  -- A→ADMIN
                    CASE rec.STATUS WHEN 'Y' THEN 1 ELSE 0 END,
                    NVL(CAST(rec.REG_DATE AS TIMESTAMP), SYSTIMESTAMP)
                );
                UPDATE CDC_TOBE_MEMBER SET PROCESSED_YN = 'Y', PROCESSED_AT = SYSTIMESTAMP WHERE CDC_SEQ = rec.CDC_SEQ;
            END IF;
            COMMIT;
        EXCEPTION
            WHEN OTHERS THEN
                v_err_msg := SUBSTR(SQLERRM, 1, 500);
                UPDATE CDC_TOBE_MEMBER SET PROCESSED_YN = 'E', ERROR_MSG = v_err_msg WHERE CDC_SEQ = rec.CDC_SEQ;
                COMMIT;
        END;
    END LOOP;

    -- 2단계: STAGING → 원본
    FOR rec IN (
        SELECT * FROM STAGING_TOBE_MEMBER WHERE PROCESSED_YN = 'N' ORDER BY STAGING_SEQ
    ) LOOP
        BEGIN
            CASE rec.OPERATION
                WHEN 'INSERT' THEN
                    INSERT INTO TB_MEMBER VALUES (rec.MEMBER_ID, rec.MEMBER_NAME, rec.EMAIL_ADDR, rec.MEMBER_TYPE_CD, rec.IS_ACTIVE, rec.CREATED_AT, NULL);
                WHEN 'UPDATE' THEN
                    UPDATE TB_MEMBER SET MEMBER_NAME = rec.MEMBER_NAME, EMAIL_ADDR = rec.EMAIL_ADDR,
                           MEMBER_TYPE_CD = rec.MEMBER_TYPE_CD, IS_ACTIVE = rec.IS_ACTIVE, UPDATED_AT = SYSTIMESTAMP WHERE MEMBER_ID = rec.MEMBER_ID;
                WHEN 'DELETE' THEN
                    DELETE FROM TB_MEMBER WHERE MEMBER_ID = rec.MEMBER_ID;
            END CASE;
            UPDATE STAGING_TOBE_MEMBER SET PROCESSED_YN = 'Y', PROCESSED_AT = SYSTIMESTAMP WHERE STAGING_SEQ = rec.STAGING_SEQ;
            v_hash := FN_GENERATE_HASH('TB_MEMBER', TO_CHAR(rec.MEMBER_ID), rec.OPERATION, rec.MEMBER_NAME || rec.EMAIL_ADDR);
            SP_RECORD_HASH(v_hash, 'TB_MEMBER', TO_CHAR(rec.MEMBER_ID));
            COMMIT;
        EXCEPTION
            WHEN DUP_VAL_ON_INDEX THEN
                UPDATE TB_MEMBER SET MEMBER_NAME = rec.MEMBER_NAME WHERE MEMBER_ID = rec.MEMBER_ID;
                UPDATE STAGING_TOBE_MEMBER SET PROCESSED_YN = 'Y' WHERE STAGING_SEQ = rec.STAGING_SEQ;
                COMMIT;
            WHEN OTHERS THEN
                v_err_msg := SUBSTR(SQLERRM, 1, 500);
                UPDATE STAGING_TOBE_MEMBER SET PROCESSED_YN = 'E', ERROR_MSG = v_err_msg WHERE STAGING_SEQ = rec.STAGING_SEQ;
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
    SP_WORKER_LEGACY_CODE;
    SP_WORKER_BOOK;
    SP_WORKER_MEMBER;
END;
/

-- ============================================
-- Oracle Scheduler Job 생성
-- 5초마다 WORKER 프로시저 실행
-- ============================================

-- 기존 Job이 있으면 삭제
BEGIN
    DBMS_SCHEDULER.DROP_JOB(job_name => 'JOB_CDC_WORKER', force => TRUE);
EXCEPTION
    WHEN OTHERS THEN NULL;  -- Job이 없으면 무시
END;
/

-- Scheduler Job 생성
BEGIN
    DBMS_SCHEDULER.CREATE_JOB(
        job_name        => 'JOB_CDC_WORKER',
        job_type        => 'PLSQL_BLOCK',
        job_action      => 'BEGIN SP_RUN_ALL_WORKERS; END;',
        start_date      => SYSTIMESTAMP,
        repeat_interval => 'FREQ=SECONDLY;INTERVAL=5',  -- 5초마다 실행
        enabled         => TRUE,
        comments        => 'CDC WORKER 프로시저 주기적 실행 (5초)'
    );
END;
/

-- Job 상태 확인용 쿼리 (참고용)
-- SELECT job_name, state, last_start_date, next_run_date FROM USER_SCHEDULER_JOBS WHERE job_name = 'JOB_CDC_WORKER';
