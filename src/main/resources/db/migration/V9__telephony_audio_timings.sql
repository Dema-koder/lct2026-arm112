-- Realistic softphone timings so the trainee can hear ringback → answer → ack.
update app_setting set setting_value = '2500' where setting_key = 'telephony.ringing_ms';
update app_setting set setting_value = '4500' where setting_key = 'telephony.connect_ms';
update app_setting set setting_value = '8000' where setting_key = 'telephony.acknowledge_ms';
