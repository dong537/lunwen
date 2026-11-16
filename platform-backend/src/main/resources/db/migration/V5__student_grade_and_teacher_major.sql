ALTER TABLE sys_user ADD COLUMN grade integer;
ALTER TABLE sys_user ADD CONSTRAINT user_grade_range CHECK (grade IS NULL OR grade BETWEEN 2000 AND 2100);
ALTER TABLE sys_user DROP CONSTRAINT student_fields_only;
ALTER TABLE sys_user ADD CONSTRAINT student_fields_only CHECK (
    (role = 'STUDENT' OR (teacher_id IS NULL AND grade IS NULL))
    AND (role <> 'ADMIN' OR major IS NULL)
);
CREATE INDEX idx_user_student_grade ON sys_user(college_code, grade, username, id) WHERE role = 'STUDENT';
