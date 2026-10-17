## ADDED Requirements

### Requirement: Events retain nullable anonymous gallery identity
Events SHALL retain a nullable person_id containing an A-managed G identifier distinct from the local track_key and from App user identity. Unknown, conflicting and unassociated people SHALL be null. Existing event facts SHALL remain immutable.

#### Scenario: B matches an A gallery profile
- **WHEN** B reports a valid matched G profile for the same local person
- **THEN** the event SHALL preserve its local track_key and publish that G profile as person_id

#### Scenario: Identity is unknown
- **WHEN** a match is absent, conflicting, invalid or refers only to a local P track
- **THEN** the adapter SHALL publish person_id as null and SHALL NOT claim a natural-person identity

#### Scenario: Old data and retries
- **WHEN** existing events are migrated or an old import without person_id is replayed
- **THEN** old rows SHALL retain null, old import digests SHALL remain compatible and no history SHALL be guessed or rewritten
