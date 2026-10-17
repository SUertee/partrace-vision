## ADDED Requirements

### Requirement: Public IP transport preserves backend authorization
The public entry SHALL use a trusted certificate for the configured IP and SHALL forward business requests to the existing loopback API without replacing its authorization.

#### Scenario: Missing authorization
- **WHEN** an unauthenticated client requests a business route
- **THEN** the entry or Backend SHALL reject the request without exposing business data

#### Scenario: Development documentation
- **WHEN** an external client requests Swagger or OpenAPI
- **THEN** the public entry SHALL return no development document

### Requirement: IP certificate renewal is automated
The deployment SHALL check renewal at least twice daily and reload the proxy after successful renewal.

#### Scenario: Certificate not issued
- **WHEN** the challenge endpoint is prepared but no trusted certificate exists
- **THEN** business API routes SHALL remain unavailable over plaintext and HTTPS SHALL NOT pretend readiness
