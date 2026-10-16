## ADDED Requirements

### Requirement: Source recordings are stored before Vision analysis
The system SHALL accept an explicitly marked source upload through the recordings API and atomically persist a pending recordings row and its durable processing job before Vision claims the work. Published facts SHALL remain immutable; legacy complete-package import SHALL remain compatible.

#### Scenario: Source accepted before inference
- **WHEN** an authorized camera client submits a complete source MP4 with source mode
- **THEN** the same recording identity SHALL exist as pending before Vision starts and SHALL NOT be reported as an already analyzed zero-event recording

#### Scenario: Pending recording queried as evidence
- **WHEN** an event, semantic or Agent fact query targets a pending recording
- **THEN** it SHALL return recording_not_ready before computing regions, detections or coverage

#### Scenario: Existing raw jobs migrated
- **WHEN** source-first persistence is added to an existing deployment
- **THEN** existing published recordings SHALL remain published and unfinished raw jobs SHALL gain pending rows without reuploading or losing media, identity or state

### Requirement: Real People recordings enter a durable queue
The system SHALL submit only complete continuous recordings from authorized SDK People events, using stable device/event identities for idempotency and preserving native detection provenance separately from algorithm facts.

#### Scenario: Duplicate SDK event
- **WHEN** the same camera event is discovered repeatedly or the App restarts
- **THEN** it SHALL reuse the same job identity and SHALL NOT publish duplicate recordings

#### Scenario: Camera has not finished its clip
- **WHEN** recording download fails or has not completed
- **THEN** no upload success SHALL be reported and the task SHALL remain retryable with a visible error

### Requirement: Vision processing owns a renewable exclusive lease
The Backend SHALL persist processing status, authorize all job/media access, and reject stale completion attempts. Heavy analysis SHALL execute outside the API process through an explicitly configured algorithm.

#### Scenario: Worker restarts
- **WHEN** a worker disappears before completion
- **THEN** its expired lease SHALL permit another authorized worker to claim the job

#### Scenario: Missing algorithm
- **WHEN** no real algorithm entry point is configured
- **THEN** the worker SHALL report configuration failure and SHALL NOT manufacture a completed analysis

### Requirement: Completion reflects published results
The system SHALL distinguish upload completion from analysis completion, publish validated complete results using the existing immutable facts contract, and expose status back to Android.

#### Scenario: Successful analysis
- **WHEN** a current lease submits valid results and real media
- **THEN** the Backend SHALL publish the complete recording result and expose completed status with its stable recording identity

#### Scenario: Native SDK clock is uncalibrated
- **WHEN** only the SDK event timestamp is available
- **THEN** the system SHALL preserve that timestamp as native metadata and SHALL NOT claim a verified UTC recording start


#### Scenario: API unavailable after source acceptance
- **WHEN** Android cannot read the status of an already accepted job during a temporary API outage
- **THEN** it SHALL preserve the accepted identity and last nonterminal state, retry status reads with bounded backoff, and SHALL NOT report a Vision failure or reupload the clip

#### Scenario: Recover an older local status-read failure
- **WHEN** a persisted accepted task is locally failed and explicitly retryable after a status-read outage
- **THEN** Android SHALL resume read-only reconciliation while preserving genuinely terminal completed and server-failed tasks

### Requirement: Rejected unaccepted sources can be explicitly downloaded again
The App SHALL allow an explicit user action to discard a local source rejected with validation error and download the same native event again only when the Backend has never accepted it. This action SHALL preserve event identity, provenance and destination, and SHALL NOT replace accepted media or revive completed, terminal Vision-failed or idempotency-conflict tasks.

#### Scenario: Decoder rejects an SDK download
- **WHEN** an unaccepted source is rejected with validation error and the user explicitly chooses download again
- **THEN** the App SHALL obtain fresh bytes for the same SDK event, retain its stable identity and require Backend validation again

#### Scenario: Accepted or conflicting source is protected
- **WHEN** a task is already accepted, completed or rejected for idempotency conflict
- **THEN** the download-again action SHALL NOT replace its submitted bytes or mark it completed
