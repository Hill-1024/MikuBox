//go:build !android

package main

// setCoreLogFile is a no-op off Android: the desktop build logs to stdout and
// host tests do not need a persisted core log.
func setCoreLogFile(string) {}
