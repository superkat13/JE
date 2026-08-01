# Third-party notices for Sage Forge design work

## `frontier-ai/llm-agent-factory`

- Source: <https://huggingface.co/frontier-ai/llm-agent-factory>
- Revision inspected: `505aa09857889bc679f2b914e2c33527051c37a8`
- Upstream metadata: `README.md` declares `cc-by-sa-4.0`; bundled `gmas-main/pyproject.toml` declares `CC-BY-SA-4.0`.
- Copyright/author metadata visible in the bundled graph project: `poliroika` / Vitalii Belof.
- Reuse in this release: no source code, prompt, dataset row, model or trained weight copied. Architectural concepts around declarative agent specifications, role/domain registries, retrieval ranking and separated coordinator/router/tool-runner/verifier/safety/memory/state responsibilities informed clean-room Sage schemas and modules.
- License caution: the inspected repository contains no standalone license or notice text. Direct code/data/model reuse is deferred pending owner/legal confirmation. See `SAGE_AGENT_FACTORY_AUDIT.md` for the file-level audit and adaptation record.

Existing Sage/Android/Brain dependencies and their previously preserved notices are unchanged by this work.
