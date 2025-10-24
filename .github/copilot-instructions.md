# Copilot Instructions for K3s-on-Phone Project

## README Maintenance Requirements

forbidden words
- enhanced productivity
- intelligent

### CRITICAL: Always Update Documentation
- **README.md**: MUST be updated whenever commands, options, or functionality changes
- **Shell Completions**: MUST be updated when new commands/options are added
- **Help Text**: MUST match actual implementation and README examples

### Documentation Style Guidelines
- **Technical and Dry**: Use precise, technical language
- **No Marketing Fluff**: Avoid words like "intelligent", "amazing", "powerful"
- **Factual Only**: State what the software does, not how great it is
- **Concise**: Use bullet points, short sentences, clear examples
- **Code-First**: Show usage examples rather than describing features

### Examples of Good vs Bad Documentation

**❌ Bad (Fluffy):**
```markdown
## Amazing AI-Powered Network Discovery
Our intelligent scanning system provides powerful parallel discovery capabilities with amazing speed and reliability!
```

**✅ Good (Technical):**
```markdown
## Network Discovery
- Parallel subnet scanning (20 concurrent connections)
- Early termination on first server found
- Endpoint validation: /status, /location, /orientation, /help
```

## Tab Completion System

- **Specificity**: Completion only activates for K3s Phone Setup scripts containing "K3s Phone Setup" and "scan-for-server" text
- **Multi-shell**: Supports both bash and zsh with context-aware suggestions
- **Security**: Never suggests sensitive tokens or keys in completion
- **Validation**: Use `./test-completions.sh` to verify synchronization
- **Installation**: User-level only via `./install-completion.sh` (no sudo required)

## Code Organization Requirements

### Setup.sh Structure
- **parse_command()**: Entry point for all CLI parsing
- **show_*_help()**: Individual help functions per command
- **handle_*_command()**: Individual command handlers
- **Consistent Error Handling**: Use log_error, provide troubleshooting

### CLI Consistency Rules
- All commands via `./setup.sh COMMAND [OPTIONS]`
- Global options: `--help`, `--version`, `--verbose`
- Command-specific help: `./setup.sh COMMAND --help`
- Options use both short/long forms: `-v`/`--verbose`

## When Making Changes

### Before Adding New Commands
1. Update completion files FIRST
2. Add help function
3. Add command handler
4. Update README with examples
5. Test completion works

### Before Changing Existing Commands
1. Check all completion files for references
2. Update help text to match new behavior
3. Update README examples
4. Verify backward compatibility or document breaking changes

### Testing Checklist
- [ ] `./setup.sh --help` shows new command
- [ ] `./setup.sh COMMAND --help` works
- [ ] Tab completion works for new command
- [ ] README examples are accurate
- [ ] No marketing language in documentation

## File Dependencies

### Core Files
- `setup.sh` - Main script, must have consistent CLI interface
- `README.md` - Must reflect all available commands and options
- `install-completion.sh` - Must work idempotently

### Completion Files
- `_k3s_setup` - Zsh completion (primary)
- `k3s-completion.sh` - Universal bash/zsh
- `k3s-setup-completion.bash` - Bash-only (legacy)

### Documentation Standards
- Use code blocks for all commands
- Show actual output when helpful
- Group related commands together
- Provide both basic and advanced examples
- Include troubleshooting steps

## Anti-Patterns to Avoid

### Documentation
- ❌ "Powerful AI-driven scanning"
- ❌ "Intelligent discovery system"  
- ❌ "Amazing parallel processing"
- ❌ "Revolutionary approach"
- ✅ "Parallel scanning (20 connections)"
- ✅ "CIDR notation support"
- ✅ "Background process management"

### Code
- ❌ Adding commands without updating completions
- ❌ Changing help text without updating README
- ❌ Breaking CLI consistency
- ❌ Adding sudo requirements
- ❌ Marketing language in user-facing text

## Summary
Keep documentation technical, accurate, and synchronized with code. Update completion files whenever commands change. Avoid marketing language.
