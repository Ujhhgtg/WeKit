UPDATE users SET release_channel='stable' WHERE release_channel='local';
DELETE FROM channels WHERE name='local';
