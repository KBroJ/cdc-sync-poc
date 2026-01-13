-- ============================================
-- ASIS Oracle 초기화 스크립트
-- 01. 원본 테이블 생성
-- ============================================

-- 레거시 코드 마스터 (ASIS→TOBE 단방향)
CREATE TABLE LEGACY_CODE (
    CODE_ID     VARCHAR2(10) PRIMARY KEY,
    CODE_NAME   VARCHAR2(100),
    USE_YN      CHAR(1) DEFAULT 'Y',
    REG_DATE    DATE DEFAULT SYSDATE
);

-- 도서 정보 (양방향)
CREATE TABLE BOOK_INFO (
    BOOK_ID     NUMBER PRIMARY KEY,
    BOOK_TITLE  VARCHAR2(200) NOT NULL,
    AUTHOR      VARCHAR2(100),
    CATEGORY    VARCHAR2(2),
    STATUS      CHAR(1) DEFAULT 'Y',
    REG_DATE    DATE DEFAULT SYSDATE,
    MOD_DATE    DATE
);

-- 회원 정보 (양방향)
CREATE TABLE MEMBER_INFO (
    MEMBER_ID   NUMBER PRIMARY KEY,
    MEMBER_NAME VARCHAR2(50) NOT NULL,
    EMAIL       VARCHAR2(100),
    MEMBER_TYPE CHAR(1),
    STATUS      CHAR(1) DEFAULT 'Y',
    REG_DATE    DATE DEFAULT SYSDATE
);

-- 신규 서비스 수신용 (TOBE→ASIS 단방향)
CREATE TABLE NEW_SERVICE_RECV (
    SERVICE_ID  NUMBER PRIMARY KEY,
    SERVICE_NM  VARCHAR2(100),
    SVC_TYPE    VARCHAR2(10),
    USE_YN      CHAR(1),
    REG_DATE    DATE
);

-- 테이블 생성 확인
SELECT table_name FROM user_tables ORDER BY table_name;
