## ADDED Requirements

### Requirement: Confirmed scope and readonly tool execution
The Agent MUST use create_agent with only the authorized readonly business tools and a fixed saved scope.

#### Scenario: A model requests data outside its run
- **WHEN** a model requests an Event or Evidence outside the confirmed Query
- **THEN** the tool rejects the request without exposing that resource or modifying facts

#### Scenario: Semantic dependency is unavailable
- **WHEN** the configured embedding service or vector store is unavailable
- **THEN** the tool reports an explicit error rather than fabricated candidates or an empty successful search

### Requirement: Durable history and terminal transitions
The Backend MUST persist owner-isolated history and atomically commit a completed run with exactly one assistant message.

#### Scenario: Stop wins the completion race
- **WHEN** a running analysis is stopped before completion commits
- **THEN** no late result changes its terminal state or writes an assistant message

#### Scenario: Credentials are revoked during execution
- **WHEN** current credentials or recording authorization no longer permit a tool or completion
- **THEN** subsequent access is denied and no unauthorized answer is published

### Requirement: Validated plans and evidence references
The Agent MUST validate plans and final structured output, and only cite available evidence actually read in the current execution.

#### Scenario: Hallucinated or unread reference
- **WHEN** generated output cites an invented, missing or unread frame
- **THEN** completion fails validation instead of silently publishing or filtering the invalid citation

### Requirement: Single execution instance and bounded work
The runtime MUST hold an exclusive execution lease, bound work, and explicitly fail interrupted runs at startup.

#### Scenario: A second execution instance starts
- **WHEN** another live instance already holds the execution lock
- **THEN** startup is rejected without marking its active runs failed

#### Scenario: Process restart with an abandoned run
- **WHEN** an instance acquires the exclusive lock and discovers old running rows
- **THEN** it marks them failed with service_restarted without automatic continuation
