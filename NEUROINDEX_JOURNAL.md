# NeuroIndex Architecture Journal

## PROJECT STATE

NeuroIndex is currently in MVP phase.

The primary objective right now is validating whether semantic conversational memory retrieval can feel significantly better than traditional RAG systems.

The current focus is:

* reliable ingestion
* semantic fragmentation
* vector storage
* semantic retrieval
* contextual reconstruction

Perfection is intentionally deferred in favor of iteration speed and architectural experimentation.

---

# CURRENT ARCHITECTURE

## Ingestion Pipeline

```text
Conversation Export
↓
Semantic Unit Extraction
↓
Semantic Fragmentation
↓
Embedding Generation
↓
pgvector Storage
↓
Semantic Retrieval
```

---

## Current Stack

* PostgreSQL
* pgvector
* Ollama
* nomic-embed-text
* Spring Boot
* Custom semantic fragmentation pipeline

---

# MAJOR ARCHITECTURAL OBSERVATIONS

## 03/06/2026

### 1. Current architecture is Claude-centric

The ingestion pipeline is currently designed around Claude export structure.

GPT exports have not yet been analyzed, so major architectural shifts are still possible.

Although some generalization has been attempted, assumptions currently exist around:

* message structure
* content blocks
* tool usage
* sequencing
* metadata layout

Potential future issue:
Different LLM providers may require fundamentally different parsing strategies.

---

### 2. Text semantic units also contain code

Claude text blocks frequently contain markdown code fences.

Example:

````markdown
Explanation text

```java
some code
````



This means:
- prose and code coexist inside the same semantic unit
- embeddings become structurally mixed
- semantic fragmentation becomes weaker
- chunk completeness degrades

Current decision:
Allow mixed ingestion for MVP.

Reason:
Separating code and prose aggressively may destroy semantic continuity between:
- prompt
- explanation
- implementation

Future direction:
Move toward structured multimodal fragments instead of strict separation.

---

### 3. Current fragmentation is semantically coherent but structurally weak

The current chunking system uses:
- cosine similarity
- centroid averaging
- semantic drift thresholds

This works reasonably for prose.

However, retrieval experiments revealed:
- fragments are often incomplete
- logical code units are split
- methods/classes can span multiple fragments
- retrieved chunks sometimes lack surrounding context

Important realization:
Semantic similarity does not guarantee semantic completeness.

Future direction:
- AST-aware code chunking
- neighborhood retrieval expansion
- graph-based contextual reconstruction

---

### 4. Sequence-based conversational assumptions are fragile

Prompt/response distinction currently relies heavily on:
- sequence ordering
- even/odd assumptions

Potential issue:
Future exports may include:
- retries
- edits
- branching
- tool outputs
- system messages
- multimodal content

This may weaken retrieval linkage between:
- user intent
- assistant implementation
- generated code

Current decision:
Accept this limitation for MVP.

Future direction:
Introduce conversational graph edges:
- ANSWERS
- CONTINUES
- IMPLEMENTS
- EXPLAINS
- CORRECTS

---

### 5. Vector similarity alone is insufficient

Current retrieval quality exposed a major insight:

Nearest semantic fragments are not always enough.

The system also needs:
- neighboring fragments
- conversational locality
- contextual continuity
- structural completeness

Future direction:
Hybrid retrieval:
- vector similarity
- graph traversal
- contextual neighborhood expansion

---

### 6. Semantic noise heavily affects retrieval quality

Observed issues:
- markdown separators
- formatting artifacts
- empty structural blocks

were entering embedding space.

This polluted vector quality and created:
- duplicate fragments
- poor retrieval
- semantic contamination

Current fix:
Semantic noise filtering based on semantic density.

---

### 7. Re-embedding full chunks was computationally expensive

Initial fragmentation approach repeatedly embedded growing chunks.

This created:
- O(n²) embedding complexity
- severe latency

Current fix:
- batch embeddings
- centroid averaging
- incremental semantic updates

Result:
Linear-time semantic fragmentation.

---

# LET GO'S

## 1. Perfect fragmentation

Current chunking is imperfect.

Reason for acceptance:
The MVP goal is retrieval validation, not perfect semantic cognition.

---

## 2. Perfect code understanding

Code is currently treated similarly to text.

Reason for acceptance:
AST-aware parsing and code-specific embeddings would significantly increase complexity.

---

## 3. Full conversational graph modeling

The graph layer is intentionally postponed.

Reason:
Need to validate whether semantic retrieval itself is valuable before investing heavily into graph cognition.

---

## 4. Strict modality separation

Text and code are currently allowed to coexist.

Reason:
Preserving semantic continuity is currently more valuable than embedding purity.

---

## 5. Provider-agnostic architecture

Current system is optimized around Claude exports.

Reason:
Early iteration speed matters more than universal compatibility.

---

# FUTURE IMPROVEMENTS

## Retrieval

- neighborhood retrieval expansion
- hybrid graph + vector retrieval
- semantic reranking
- query intent detection
- contextual reconstruction

---

## Fragmentation

- AST-aware code chunking
- structure-aware fragmentation
- markdown-aware segmentation
- adaptive chunk sizing
- token-aware boundaries

---

## Graph Layer

- keyword extraction
- semantic nodes
- centroid clusters
- co-occurrence graphs
- conversational causality edges

---

## Embeddings

- code-specific embeddings
- multimodal embeddings
- centroid persistence
- embedding caching
- semantic compression

---

## Memory Architecture

- long-term semantic nodes
- evolving centroids
- topic hierarchy
- temporal memory evolution
- semantic memory neighborhoods

---

# KEY INSIGHTS

## Semantic similarity != semantic completeness

A retrieved fragment may be semantically similar yet structurally incomplete.

---

## Conversations distribute meaning across multiple messages

Meaning is often spread across:
- prompts
- responses
- explanations
- code
- corrections

---

## Vector retrieval alone is insufficient for memory reconstruction

Semantic neighborhoods and graph relationships are necessary for high-quality contextual recall.

---

## Mixed modality retrieval may actually be useful

Strict separation of prose and code may weaken contextual cognition.

---

## Fragment quality determines retrieval quality more than embedding quality

Bad chunk boundaries can significantly degrade otherwise good embeddings.

---

# CURRENT MVP GOAL

Validate whether conversational semantic memory retrieval:
- feels useful
- feels contextual
- feels better than naive RAG
- preserves conversational continuity


