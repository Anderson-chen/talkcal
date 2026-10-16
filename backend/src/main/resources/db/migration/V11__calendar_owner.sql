-- 行程和 AI 助理的對話都有擁有者（#104）：只看得到、改得到自己的。
--
-- owner_id 就是登入者的帳號 ID（account.id），但不加外鍵：
-- calendar 和 auth 兩個模組在資料庫裡也互不認識（跟 V2 的決定、ArchitectureTest 的模組規則一致），
-- calendar 只知道「這是某個擁有者」。代價：刪帳號時要另外清掉他的行程和對話（目前還沒有刪帳號的功能）。

-- 既有的資料沒有擁有者，也不知道該給誰：都是開發、測試時留下的，直接刪掉，不猜一個擁有者塞進去
DELETE FROM calendar_event;
DELETE FROM calendar_assistant_message;

ALTER TABLE calendar_event ADD COLUMN owner_id UUID NOT NULL;

-- 月曆查的變成「這個人、跟這段期間有重疊的行程」。V3 的索引只有期間：
-- 規劃器得先把所有人在這段期間的行程都挑出來，再一筆一筆濾掉別人的 —— 人越多越慢。
-- 改成 owner_id 和期間一起放進同一個 GiST 索引，一次就只走這個人的那幾筆。
-- GiST 本身不支援 UUID 的「等於」，btree_gist 補上這個（PostgreSQL 內建的擴充，13 版起一般的資料庫擁有者就能裝）
CREATE EXTENSION IF NOT EXISTS btree_gist;
DROP INDEX calendar_event_period;
CREATE INDEX calendar_event_owner_period ON calendar_event USING gist (owner_id, tsrange(start_at, end_at));

-- 對話依擁有者分開：主鍵加上 owner_id，同一個 conversation_id 在不同人底下是不同的兩段對話。
-- 這樣別人拿到你的 conversation_id 也沒用：他讀到的是自己名下那段（空的），存檔也只會寫進自己名下，
-- 碰不到、蓋不掉你的那段 —— 不必另外寫「這段對話是不是你的」的檢查，每句 SQL 帶著 owner_id 就是檢查
ALTER TABLE calendar_assistant_message ADD COLUMN owner_id UUID NOT NULL;
ALTER TABLE calendar_assistant_message DROP CONSTRAINT calendar_assistant_message_pkey;
ALTER TABLE calendar_assistant_message ADD PRIMARY KEY (owner_id, conversation_id, position);
