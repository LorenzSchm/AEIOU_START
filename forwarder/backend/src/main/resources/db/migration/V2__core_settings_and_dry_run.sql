-- Core: default runtime settings (see SettingsService) and ids for dry-run mails.

INSERT INTO setting (key, value, updated_by) VALUES
    ('bidWaitSeconds',            '90',   'migration'),
    ('routerConfidenceThreshold', '0.75', 'migration'),
    ('autopilot',                 'false', 'migration'), -- answer live mails; off until a human turns it on
    ('pollingEnabled',            'true', 'migration'),  -- runtime switch on top of app.poller.enabled
    ('quoteDeadlineSeconds',      '300',  'migration'),
    ('portalEnabled',             'true', 'migration'),
    ('bidsEnabled',               'true', 'migration')
ON CONFLICT (key) DO NOTHING;

-- Dry-run mails (app.mail.send-enabled=false) are stored with external_id = -nextval(...),
-- so they never collide with real 5U mailbox ids.
CREATE SEQUENCE dry_run_mail_seq START WITH 1;
