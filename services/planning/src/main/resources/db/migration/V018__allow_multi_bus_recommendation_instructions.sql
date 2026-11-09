ALTER TABLE planning.issued_instruction DROP CONSTRAINT uq_issued_instruction_proposal;
CREATE UNIQUE INDEX uq_issued_instruction_proposal_bus ON planning.issued_instruction(proposal_id, bus_id);
