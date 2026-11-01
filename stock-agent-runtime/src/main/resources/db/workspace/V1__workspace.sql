CREATE TABLE IF NOT EXISTS workspace_conversation (
 id VARCHAR(36) PRIMARY KEY, title VARCHAR(200) NOT NULL, symbol VARCHAR(20) NOT NULL,
 as_of VARCHAR(10) NOT NULL, scope VARCHAR(20) NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 INDEX ix_workspace_conversation_updated(updated_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS workspace_task (
 id VARCHAR(36) PRIMARY KEY, conversation_id VARCHAR(36) NOT NULL, question TEXT NOT NULL,
 request_json MEDIUMTEXT NOT NULL, snapshot_json MEDIUMTEXT NOT NULL, status VARCHAR(32) NOT NULL,
 owner_id VARCHAR(36) NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 INDEX ix_workspace_task_conversation(conversation_id,created_at,id),
 FOREIGN KEY (conversation_id) REFERENCES workspace_conversation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS workspace_message (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, conversation_id VARCHAR(36) NOT NULL, task_id VARCHAR(36) NOT NULL,
 role VARCHAR(16) NOT NULL, content MEDIUMTEXT NOT NULL, created_at BIGINT NOT NULL,
 UNIQUE KEY uq_workspace_message_role(task_id,role), INDEX ix_workspace_message_conversation(conversation_id,id),
 FOREIGN KEY (conversation_id) REFERENCES workspace_conversation(id),
 FOREIGN KEY (task_id) REFERENCES workspace_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
