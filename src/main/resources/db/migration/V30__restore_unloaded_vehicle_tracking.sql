-- An unloaded vehicle leaves the tracked chunk; it is not a destroyed vehicle.
-- Earlier imports marked both events as inactive, so restore the latest unloaded state.
update t_vehicle_current_state
set active = true,
    destroyed_at = null
where destroyed_at is not null
  and exists (
    select 1
    from t_vehicle_position_transaction vehicle
    where vehicle.vehicle_entity_id = t_vehicle_current_state.vehicle_entity_id
      and vehicle.occurred_at = t_vehicle_current_state.last_updated
      and vehicle.event_type = 'VEHICLE_REMOVED'
      and lower(trim(coalesce(vehicle.removal_reason, ''))) = 'unloaded'
  );
