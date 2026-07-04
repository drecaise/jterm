# Regenerates the Mermaid views committed under generated/ from workspace.dsl.
# Requires the Structurizr CLI (https://github.com/structurizr/cli) on PATH.

STRUCTURIZR ?= structurizr-cli
WORKSPACE   := workspace.dsl
OUT_DIR     := generated

.PHONY: export clean check tools

export:
	@mkdir -p $(OUT_DIR)
	$(STRUCTURIZR) export -workspace $(WORKSPACE) -format mermaid -output $(OUT_DIR)

# CI drift check: regenerate and fail if committed output differs.
check: export
	@git diff --exit-code -- $(OUT_DIR)

clean:
	@rm -f $(OUT_DIR)/*.mmd

tools:
	@command -v $(STRUCTURIZR) >/dev/null 2>&1 || { \
	  echo "structurizr-cli not found on PATH."; \
	  echo "Install: https://github.com/structurizr/cli/releases (unpack + symlink structurizr.sh as 'structurizr-cli')."; \
	  exit 1; \
	}
