-- V219: each stored Ask PhotoCast ready answer records the Claude model that wrote it. The model is now
-- chosen on Operations -> Run Config (the ASK_READY model_selection row, V218), so once it is switched
-- nobody could otherwise tell which stored answers came from which model.
--
-- Every answer stored so far was written on Haiku 4.5: production's Ask model has always been HAIKU
-- (the retired photocast.ask.model key). The existing rows are therefore backfilled 'HAIKU'. The column
-- stays nullable: a row written without a model reads as unknown rather than being mislabelled.

ALTER TABLE ask_ready_answer ADD COLUMN evaluation_model VARCHAR(10);

UPDATE ask_ready_answer SET evaluation_model = 'HAIKU';
