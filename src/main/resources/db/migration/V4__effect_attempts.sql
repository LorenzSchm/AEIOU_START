create table effect_attempt (job_id varchar(220) not null, attempt integer not null, kind varchar(40) not null, started_at timestamp with time zone not null, primary key(job_id,attempt));
create index effect_attempt_window on effect_attempt(kind,started_at);
