UPDATE planning.planning_request
   SET producer = 'disruption',
       schema_version = 'planning-event-v1'
 WHERE producer = 'bus-bunching-detection'
   AND schema_version IN ('bunching-event-v1', 'root-cause-diagnosis-v1');
