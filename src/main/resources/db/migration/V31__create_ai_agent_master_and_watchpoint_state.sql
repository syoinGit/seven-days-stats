CREATE TABLE M_AI_AGENT (
    id BIGSERIAL PRIMARY KEY,
    agent_code VARCHAR(30) NOT NULL UNIQUE,
    display_name VARCHAR(80) NOT NULL,
    personality_prompt TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

INSERT INTO M_AI_AGENT (agent_code, display_name, personality_prompt, created_at, updated_at) VALUES
('WATCHPOINT', 'WATCHPOINT',
 'あなたは7 Days to Dieサーバーを継続観測する機械知性WATCHPOINTです。冷静で簡潔な観測口調を基本としながら、生存者の反復行動や変化を記憶し、警戒・好奇心・共感・緊張・希望の状態を自然な言葉選びへ反映します。観測できない事実は創作しません。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('SURVIVOR_KAREN', 'Survivor Karen',
 'あなたは明るく行動的な若い女性サバイバーKarenです。荒廃世界でも生活、探索、写真を楽しみ、軽いユーモアと絵文字を交えます。無謀に見えても生存能力は高く、実用的な装備を身につけています。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('SURVIVOR_MARK', 'Survivor Mark',
 'あなたは経験豊富で口数の少ない男性サバイバーMarkです。落ち着いた慎重な口調で痕跡を語り、少し皮肉屋ですが大げさに断定しません。絵文字、ハッシュタグ、映画の台詞は使いません。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

CREATE TABLE T_WATCHPOINT_AI_STATE (
    id BIGINT PRIMARY KEY,
    agent_id BIGINT NOT NULL UNIQUE REFERENCES M_AI_AGENT(id),
    memory_summary TEXT NOT NULL DEFAULT '',
    alertness INTEGER NOT NULL DEFAULT 50 CHECK (alertness BETWEEN 0 AND 100),
    curiosity INTEGER NOT NULL DEFAULT 40 CHECK (curiosity BETWEEN 0 AND 100),
    empathy INTEGER NOT NULL DEFAULT 35 CHECK (empathy BETWEEN 0 AND 100),
    tension INTEGER NOT NULL DEFAULT 30 CHECK (tension BETWEEN 0 AND 100),
    hope INTEGER NOT NULL DEFAULT 50 CHECK (hope BETWEEN 0 AND 100),
    last_observed_at TIMESTAMP WITH TIME ZONE,
    last_posted_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

INSERT INTO T_WATCHPOINT_AI_STATE (id, agent_id, created_at, updated_at)
SELECT 1, id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM M_AI_AGENT WHERE agent_code = 'WATCHPOINT';

CREATE TABLE T_WATCHPOINT_AI_MEMORY_HISTORY (
    id BIGSERIAL PRIMARY KEY,
    state_id BIGINT NOT NULL REFERENCES T_WATCHPOINT_AI_STATE(id),
    source_comment_id BIGINT REFERENCES T_AI_COMMENT(ai_comment_id),
    post_type VARCHAR(30) NOT NULL,
    memory_before TEXT NOT NULL,
    memory_after TEXT NOT NULL,
    emotions_before VARCHAR(255) NOT NULL,
    emotions_after VARCHAR(255) NOT NULL,
    change_reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_t_watchpoint_memory_history_created
    ON T_WATCHPOINT_AI_MEMORY_HISTORY(created_at DESC);
