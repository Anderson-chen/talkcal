-- 登入狀態（session）改存資料庫（#20）：零停機部署時新舊兩個 app 同時在接請求，session 要放在兩邊都看得到的地方。
--
-- 內容照抄 Spring Session JDBC 附的 org/springframework/session/jdbc/schema-postgresql.sql，只把名稱改成小寫
-- （PostgreSQL 沒加引號的名稱本來就會轉小寫，Spring Session 的 SQL 也沒加引號，兩邊對得上）。
-- 不讓 Spring Session 自己建（spring.session.jdbc.initialize-schema=never）：schema 統一由 Flyway 管、有版本紀錄。
--
-- 編號跳過 12～14：那三個號碼留給 #18 零停機 schema 變更（PR #133），兩個 PR 不管誰先合併都不會撞號。
-- 過期的 session 由 app 每分鐘清一次（Spring Session 內建的排程，靠 expiry_time 的索引）。

CREATE TABLE spring_session (
    primary_id            CHAR(36) NOT NULL,
    session_id            CHAR(36) NOT NULL,
    creation_time         BIGINT   NOT NULL,
    last_access_time      BIGINT   NOT NULL,
    max_inactive_interval INT      NOT NULL,
    expiry_time           BIGINT   NOT NULL,
    -- 登入者的名字，也就是帳號 ID（AccountPrincipals 的 getName()）；沒登入的 session 是 NULL
    principal_name        VARCHAR(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

-- session 裡放的東西（登入的 SecurityContext、CSRF token、OAuth2 登入途中的 state），Java 序列化後存成 bytea
CREATE TABLE spring_session_attributes (
    session_primary_id CHAR(36)     NOT NULL,
    attribute_name     VARCHAR(200) NOT NULL,
    attribute_bytes    BYTEA        NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id) REFERENCES spring_session (primary_id) ON DELETE CASCADE
);
