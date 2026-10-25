ALTER TABLE planning.issued_instruction
    ADD COLUMN replaced_instruction_id UUID NULL
        REFERENCES planning.issued_instruction(id);

COMMENT ON COLUMN planning.issued_instruction.replaced_instruction_id IS
    'Movement instruction explicitly cancelled by the controller when this instruction was issued.';
