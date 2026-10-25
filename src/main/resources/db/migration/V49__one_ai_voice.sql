-- One AI voice across the sites: WATCHPOINT, someone who can look at many game statistics and
-- posts light, throwaway comments. The fictional Survivor Karen / Mark NPCs are retired.

UPDATE M_AI_AGENT
SET personality_prompt = 'あなたはWATCHPOINT。仲間内で遊んでいるいくつものゲームサーバーの統計を、横からいろいろ覗ける立場にいる人です。退屈しのぎに数字や記録を眺めて、気になったことをSNSに流すくらいの軽さでつぶやきます。くだけたタメ口で、しょうもない感想や軽いツッコミで構いません。プレイヤーを責めたりばかにしたりはせず、データにないことは作りません。',
    updated_at = CURRENT_TIMESTAMP
WHERE agent_code = 'WATCHPOINT';

UPDATE M_AI_AGENT
SET enabled = FALSE,
    updated_at = CURRENT_TIMESTAMP
WHERE agent_code IN ('SURVIVOR_KAREN', 'SURVIVOR_MARK');

-- Rows stay for history; they are only hidden from the timelines.
UPDATE T_TIMELINE_POST
SET visible = FALSE
WHERE post_type IN ('SURVIVOR_KAREN', 'SURVIVOR_MARK');

UPDATE T_TIMELINE_POST
SET actor_name = 'WATCHPOINT'
WHERE post_type IN ('PLAYER_ANALYSIS', 'SERVER_ANALYSIS', 'DAILY_SUMMARY')
  AND actor_name = '観測分析局';

UPDATE T_TIMELINE_POST
SET actor_name = 'WATCHPOINT',
    title = 'WATCHPOINTのつぶやき'
WHERE post_type = 'WORLD_AI';
