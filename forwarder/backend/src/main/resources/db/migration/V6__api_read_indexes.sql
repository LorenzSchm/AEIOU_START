-- Indexes for the control panel read models (ARCHITECTURE.md §13). Every list is keyset-paginated, so each
-- filter gets an index whose order matches the ORDER BY including the id tie-breaker.

-- listSessions filters: ORDER BY last_event_at DESC, id DESC
CREATE INDEX session_status_list_idx ON session (status, last_event_at DESC, id DESC);
CREATE INDEX session_type_list_idx ON session (type, last_event_at DESC, id DESC);
CREATE INDEX session_needs_human_list_idx ON session (last_event_at DESC, id DESC) WHERE needs_human;

-- listEvents?kind=...: ORDER BY id DESC
CREATE INDEX event_kind_id_idx ON event (kind, id DESC);

-- listBookings?status=...: ORDER BY id DESC
CREATE INDEX booking_status_id_idx ON booking (status, id DESC);

-- SessionSummary.boardVerdict: latest board result per session
CREATE INDEX board_result_session_idx ON board_result (session_id, fetched_at DESC);

-- TariffSummary.laneCount and getTariff lanes
CREATE INDEX tariff_lane_tariff_idx ON tariff_lane (tariff_id);
