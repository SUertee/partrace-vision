# agent-skills Specification

## Purpose
规定 Agent 内置只读业务 skill 的发现、按需加载、语义服务不可用处理及既有证据与执行预算边界。代码和离线验证见归档变更 `2026-10-17-d07-agent-skills`；不表示外部模型或 D10 已验收。

## Requirements

### Requirement: Bounded skill discovery and loading
The Agent SHALL expose a fixed built-in skill catalog and load its instructions through a budgeted read-only tool without widening the confirmed scope.

#### Scenario: Load a known skill
- **WHEN** the model requests a registered skill name
- **THEN** it receives the corresponding instructions and allowed tool names, without executing a fact lookup or granting new capabilities

#### Scenario: Load an unknown name or path
- **WHEN** the model supplies an unknown skill name or filesystem path
- **THEN** it receives an explicit unknown-skill result without file access or business tool execution

### Requirement: Explicit optional semantic outage
The Agent SHALL distinguish an unavailable semantic encoder from an empty or unindexed search and may continue using authorized ordinary event reads.

#### Scenario: Encoder unavailable during a skill workflow
- **WHEN** the scoped semantic service reports model_unavailable after valid authorization
- **THEN** the tool returns an explicit unavailable result without candidates or coverage counts, repeated attempts do not call that dependency again during the run, and the final answer discloses the limitation

#### Scenario: Authorization or storage failure
- **WHEN** permissions are revoked, a query is out of scope, or a non-encoder failure occurs
- **THEN** execution terminates without treating that failure as an optional semantic outage

### Requirement: Existing evidence and lifecycle controls
The Agent MUST apply existing tool budgets, cancellation and evidence-reference validation to skill-guided work.

#### Scenario: A skill is loaded without reading evidence
- **WHEN** the answer cites a frame after only loading instructions
- **THEN** reference validation fails because no event and available evidence were actually read
