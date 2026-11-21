-- Reference airports for the globe and for resolving city names in tariff sheets.
-- Covers the German origins (pickup_zones.csv) and every destination in carrier-list.csv and the kit tariffs.
INSERT INTO airport (iata, name, city, country, lat, lon) VALUES
    ('FRA', 'Frankfurt Airport', 'Frankfurt', 'DE', 50.0333, 8.5706),
    ('MUC', 'Munich Airport', 'Munich', 'DE', 48.3538, 11.7861),
    ('DUS', 'Duesseldorf Airport', 'Duesseldorf', 'DE', 51.2895, 6.7668),
    ('STR', 'Stuttgart Airport', 'Stuttgart', 'DE', 48.6899, 9.2220),
    ('CGN', 'Cologne Bonn Airport', 'Cologne', 'DE', 50.8659, 7.1427),
    ('HAM', 'Hamburg Airport', 'Hamburg', 'DE', 53.6304, 9.9882),
    ('ASU', 'Silvio Pettirossi International Airport', 'Asuncion', 'PY', -25.2400, -57.5191),
    ('ATL', 'Hartsfield-Jackson Atlanta International Airport', 'Atlanta', 'US', 33.6407, -84.4277),
    ('BKK', 'Suvarnabhumi Airport', 'Bangkok', 'TH', 13.6900, 100.7501),
    ('BOG', 'El Dorado International Airport', 'Bogota', 'CO', 4.7016, -74.1469),
    ('BOM', 'Chhatrapati Shivaji Maharaj International Airport', 'Mumbai', 'IN', 19.0896, 72.8656),
    ('CAI', 'Cairo International Airport', 'Cairo', 'EG', 30.1219, 31.4056),
    ('DEL', 'Indira Gandhi International Airport', 'Delhi', 'IN', 28.5562, 77.1000),
    ('DFW', 'Dallas/Fort Worth International Airport', 'Dallas', 'US', 32.8998, -97.0403),
    ('DOH', 'Hamad International Airport', 'Doha', 'QA', 25.2731, 51.6081),
    ('DXB', 'Dubai International Airport', 'Dubai', 'AE', 25.2532, 55.3657),
    ('EZE', 'Ministro Pistarini International Airport', 'Buenos Aires', 'AR', -34.8222, -58.5358),
    ('GRU', 'Sao Paulo/Guarulhos International Airport', 'Sao Paulo', 'BR', -23.4356, -46.4731),
    ('HKG', 'Hong Kong International Airport', 'Hong Kong', 'HK', 22.3080, 113.9185),
    ('ICN', 'Incheon International Airport', 'Seoul', 'KR', 37.4602, 126.4407),
    ('JFK', 'John F. Kennedy International Airport', 'New York', 'US', 40.6413, -73.7781),
    ('JNB', 'O. R. Tambo International Airport', 'Johannesburg', 'ZA', -26.1367, 28.2411),
    ('KTM', 'Tribhuvan International Airport', 'Kathmandu', 'NP', 27.6966, 85.3591),
    ('KUL', 'Kuala Lumpur International Airport', 'Kuala Lumpur', 'MY', 2.7456, 101.7099),
    ('LAX', 'Los Angeles International Airport', 'Los Angeles', 'US', 33.9416, -118.4085),
    ('LIM', 'Jorge Chavez International Airport', 'Lima', 'PE', -12.0219, -77.1143),
    ('LOS', 'Murtala Muhammed International Airport', 'Lagos', 'NG', 6.5774, 3.3212),
    ('MEL', 'Melbourne Airport', 'Melbourne', 'AU', -37.6690, 144.8410),
    ('MEX', 'Mexico City International Airport', 'Mexico City', 'MX', 19.4361, -99.0719),
    ('MIA', 'Miami International Airport', 'Miami', 'US', 25.7959, -80.2870),
    ('MNL', 'Ninoy Aquino International Airport', 'Manila', 'PH', 14.5086, 121.0194),
    ('NBO', 'Jomo Kenyatta International Airport', 'Nairobi', 'KE', -1.3192, 36.9278),
    ('NRT', 'Narita International Airport', 'Tokyo', 'JP', 35.7720, 140.3929),
    ('ORD', 'O''Hare International Airport', 'Chicago', 'US', 41.9742, -87.9073),
    ('PEK', 'Beijing Capital International Airport', 'Beijing', 'CN', 40.0799, 116.6031),
    ('PPT', 'Faa''a International Airport', 'Papeete', 'PF', -17.5537, -149.6066),
    ('PVG', 'Shanghai Pudong International Airport', 'Shanghai', 'CN', 31.1443, 121.8083),
    ('RUH', 'King Khalid International Airport', 'Riyadh', 'SA', 24.9576, 46.6988),
    ('SCL', 'Arturo Merino Benitez International Airport', 'Santiago', 'CL', -33.3930, -70.7858),
    ('SGN', 'Tan Son Nhat International Airport', 'Ho Chi Minh City', 'VN', 10.8188, 106.6520),
    ('SIN', 'Singapore Changi Airport', 'Singapore', 'SG', 1.3644, 103.9915),
    ('SYD', 'Sydney Kingsford Smith Airport', 'Sydney', 'AU', -33.9399, 151.1753),
    ('TLV', 'Ben Gurion Airport', 'Tel Aviv', 'IL', 32.0055, 34.8854),
    ('TNR', 'Ivato International Airport', 'Antananarivo', 'MG', -18.7969, 47.4788),
    ('TPE', 'Taiwan Taoyuan International Airport', 'Taipei', 'TW', 25.0797, 121.2342),
    ('ULN', 'Chinggis Khaan International Airport', 'Ulaanbaatar', 'MN', 47.6469, 106.8197),
    ('YYZ', 'Toronto Pearson International Airport', 'Toronto', 'CA', 43.6777, -79.6248)
ON CONFLICT (iata) DO UPDATE
    SET name = EXCLUDED.name, city = EXCLUDED.city, country = EXCLUDED.country,
        lat = EXCLUDED.lat, lon = EXCLUDED.lon;

-- Sheets quoted in a foreign currency may state their own rate (EUR per 1 unit of the sheet currency).
ALTER TABLE tariff ADD COLUMN fx_eur_per_unit NUMERIC(12, 6);

-- The kit is imported once; this keeps the import idempotent even if two instances start together.
CREATE UNIQUE INDEX tariff_kit_file_uidx ON tariff (source_file) WHERE source = 'kit';
CREATE INDEX tariff_status_idx ON tariff (status, airline_name, valid_from);
