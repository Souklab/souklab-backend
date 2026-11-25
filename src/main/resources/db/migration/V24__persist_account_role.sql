ALTER TABLE users ADD COLUMN account_role varchar(20) NULL;

UPDATE users u
SET u.account_role = CASE
    WHEN EXISTS (
        SELECT 1 FROM user_permissions up
        JOIN permissions p ON p.id = up.permission_id
        WHERE up.user_id = u.id AND p.permission_key = 'permission:admin:users'
    ) THEN 'ADMIN'
    WHEN EXISTS (SELECT 1 FROM artisans a WHERE a.id = u.id AND a.deleted_at IS NULL) THEN 'ARTISAN'
    WHEN EXISTS (SELECT 1 FROM clients c WHERE c.id = u.id AND c.deleted_at IS NULL) THEN 'CLIENT'
    ELSE 'CLIENT'
END;

ALTER TABLE users MODIFY COLUMN account_role varchar(20) NOT NULL DEFAULT 'CLIENT';
CREATE INDEX idx_users_account_role ON users (account_role);
