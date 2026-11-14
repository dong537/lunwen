-- Student accounts can use the thesis workflow without a mandatory password change.
-- Keep existing password hashes unchanged; teacher and administrator policy is unaffected.
UPDATE sys_user
SET must_change_password = false
WHERE role = 'STUDENT' AND must_change_password = true;
