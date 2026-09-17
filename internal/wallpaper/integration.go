package wallpaper

import "io"

// NewConfiguredModule builds an archive module for an in-process job using
// the same defaults and environment resolution as the CLI. No work is run
// until Sync is called. Credentials are never part of the returned UI state.
func NewConfiguredModule(source, stateDir, provider string, getenv func(string) string) (*Module, error) {
	ec := resolveEnv(source, stateDir, getenv)
	if provider != "" {
		ec.ProviderName = provider
	}
	return buildCLIModule(ec, io.Discard, false)
}
