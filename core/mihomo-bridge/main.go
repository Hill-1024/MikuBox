package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"encoding/json"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/common/utils"
	"github.com/metacubex/mihomo/common/yaml"
	"github.com/metacubex/mihomo/component/profile/cachefile"
	"github.com/metacubex/mihomo/config"
	constant "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/hub/executor"
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/tunnel/statistic"
	tun "github.com/metacubex/sing-tun"
)

var core = struct {
	sync.Mutex
	running    bool
	lastErr    string
	groupOrder []string
	effective  map[string]any
}{}

// bridgeRevision identifies the compiled bridge in exported diagnostics; bump
// it whenever the native side changes so a log proves which build produced it.
const bridgeRevision = "2026-09-17.3"

// MihomoStart initializes the Alpha core in-process. The Android app owns the
// VPN interface and passes its already-open descriptor to Mihomo's TUN inbound.
//
//export MihomoStart
func MihomoStart(configText *C.char, homeDir *C.char, tunFD C.int, dnsOverride *C.char, overridesJson *C.char) C.int {
	core.Lock()
	defer core.Unlock()

	if core.running {
		executor.Shutdown()
		core.running = false
	}

	order, err := start(C.GoString(configText), C.GoString(homeDir), int(tunFD), C.GoString(dnsOverride), C.GoString(overridesJson))
	if err != nil {
		core.lastErr = err.Error()
		core.groupOrder = nil
		return 1
	}

	core.lastErr = ""
	core.groupOrder = order
	core.running = true
	return 0
}

// MihomoStop closes listeners before Android closes its ParcelFileDescriptor.
//
//export MihomoStop
func MihomoStop() {
	core.Lock()
	defer core.Unlock()
	if core.running {
		executor.Shutdown()
		core.running = false
	}
}

// MihomoLastError returns the latest startup/configuration failure.
//
//export MihomoLastError
func MihomoLastError() *C.char {
	core.Lock()
	defer core.Unlock()
	return C.CString(core.lastErr)
}

// MihomoVersion exposes the bundled core version for user-agent and diagnostics.
//
//export MihomoVersion
func MihomoVersion() *C.char {
	return C.CString("v" + constant.Version)
}

// MihomoTraffic exposes the core's traffic accounting without requiring an
// HTTP controller to be enabled in a user configuration.
//
//export MihomoTraffic
func MihomoTraffic() *C.char {
	uplink, downlink := statistic.DefaultManager.Now()
	uploadTotal, downloadTotal := statistic.DefaultManager.Total()
	payload, _ := json.Marshal(struct {
		Upload        int64 `json:"upload"`
		Download      int64 `json:"download"`
		UploadTotal   int64 `json:"uploadTotal"`
		DownloadTotal int64 `json:"downloadTotal"`
	}{uplink, downlink, uploadTotal, downloadTotal})
	return C.CString(string(payload))
}

// MihomoProxies mirrors the RESTful controller's GET /proxies, returning every
// proxy and group as JSON so the app can render a node list without opening a
// controller port. Returns {"proxies": {...}} or an empty string on failure.
//
//export MihomoProxies
func MihomoProxies() *C.char {
	payload, err := json.Marshal(map[string]any{
		"proxies": tunnel.Proxies(),
	})
	if err != nil {
		return C.CString("")
	}
	return C.CString(string(payload))
}

// MihomoSelectProxy points a selector group at one of its members, mirroring
// PUT /proxies/{group}. An empty name clears a pinned node on auto groups
// (url-test/fallback), returning them to automatic selection. Returns 0 on
// success, 1 on failure (see MihomoLastError).
//
//export MihomoSelectProxy
func MihomoSelectProxy(groupName *C.char, proxyName *C.char) C.int {
	core.Lock()
	defer core.Unlock()

	group := C.GoString(groupName)
	name := C.GoString(proxyName)

	proxy, exist := tunnel.Proxies()[group]
	if !exist {
		core.lastErr = "unknown proxy group: " + group
		return 1
	}
	selector, ok := proxy.Adapter().(outboundgroup.SelectAble)
	if !ok {
		core.lastErr = group + " is not a selector"
		return 1
	}
	if name == "" {
		selector.ForceSet("")
		cachefile.Cache().SetSelected(group, "")
		core.lastErr = ""
		return 0
	}
	if err := selector.Set(name); err != nil {
		core.lastErr = err.Error()
		return 1
	}
	cachefile.Cache().SetSelected(group, name)
	core.lastErr = ""
	return 0
}

// MihomoGroupOrder returns the proxy-group names in the order the running
// configuration declared them, as a JSON array. The REST proxies map is
// alphabetical, so the app uses this to render groups like the config author
// intended. Returns "[]" before the first start or for group-less configs.
//
//export MihomoGroupOrder
func MihomoGroupOrder() *C.char {
	core.Lock()
	defer core.Unlock()
	order, _ := json.Marshal(core.groupOrder)
	return C.CString(string(order))
}

// MihomoRuntimeInfo reports the effective core configuration and build facts
// (stack, MTU, DNS mode, whether gVisor is compiled in, last error) without
// exposing node credentials, so a log export can prove how the core was
// actually configured. Returns a JSON object.
//
//export MihomoRuntimeInfo
func MihomoRuntimeInfo() *C.char {
	core.Lock()
	defer core.Unlock()
	info := map[string]any{
		"bridge_revision": bridgeRevision,
		"with_gvisor":     tun.WithGVisor,
		"running":         core.running,
		"last_error":      core.lastErr,
	}
	for key, value := range core.effective {
		info[key] = value
	}
	payload, _ := json.Marshal(info)
	return C.CString(string(payload))
}

// MihomoRules mirrors GET /rules, exposing the parsed rule list (type, payload,
// target policy) for a read-only routing viewer. Returns "[]" when stopped.
//
//export MihomoRules
func MihomoRules() *C.char {
	type ruleInfo struct {
		Type    string `json:"type"`
		Payload string `json:"payload"`
		Target  string `json:"target"`
	}
	rawRules := tunnel.Rules()
	rules := make([]ruleInfo, 0, len(rawRules))
	for _, raw := range rawRules {
		rules = append(rules, ruleInfo{
			Type:    raw.RuleType().String(),
			Payload: raw.Payload(),
			Target:  raw.Adapter(),
		})
	}
	payload, _ := json.Marshal(rules)
	return C.CString(string(payload))
}

// MihomoProxyDelay URL-tests a single proxy, mirroring GET /proxies/{name}/delay.
// Returns {"delay": ms} on success or {"error": "..."} on failure/timeout.
//
//export MihomoProxyDelay
func MihomoProxyDelay(proxyName *C.char, testURL *C.char, timeoutMS C.int) *C.char {
	name := C.GoString(proxyName)
	url := C.GoString(testURL)

	proxy, exist := tunnel.Proxies()[name]
	if !exist {
		return C.CString(`{"error":"unknown proxy"}`)
	}

	expectedStatus, _ := utils.NewUnsignedRanges[uint16]("")
	ctx, cancel := context.WithTimeout(context.Background(), time.Millisecond*time.Duration(int(timeoutMS)))
	defer cancel()

	delay, err := proxy.URLTest(ctx, url, expectedStatus)
	if err != nil || delay == 0 {
		return C.CString(`{"error":"timeout"}`)
	}
	payload, _ := json.Marshal(map[string]any{"delay": delay})
	return C.CString(string(payload))
}

// MihomoValidateDns checks that a DNS override block is well-formed YAML that
// unmarshals to a mapping. Returns an empty string when valid (or blank), or a
// human-readable error otherwise, so the editor can reject bad input up front.
//
//export MihomoValidateDns
func MihomoValidateDns(dnsYaml *C.char) *C.char {
	text := strings.TrimSpace(C.GoString(dnsYaml))
	if text == "" {
		return C.CString("")
	}
	dns := map[string]any{}
	if err := yaml.Unmarshal([]byte(text), &dns); err != nil {
		return C.CString(err.Error())
	}
	return C.CString("")
}

func start(configText, homeDir string, tunFD int, dnsOverride, overridesJson string) ([]string, error) {
	constant.SetHomeDir(homeDir)
	// Path.Config() is a bare relative name that the mihomo CLI resolves
	// against its own working directory. Android app processes run with a
	// read-only "/" as cwd, so the initial config.yaml write would fail;
	// resolve it to an absolute path the same way the CLI does.
	constant.SetConfig(filepath.Join(homeDir, "config.yaml"))
	if err := config.Init(homeDir); err != nil {
		return nil, err
	}
	// The persisted core log covers exactly one run and backs log exports.
	setCoreLogFile(filepath.Join(homeDir, "core.log"))

	// Keep the user's Mihomo configuration intact, but Android owns these TUN
	// fields because it created the interface and its descriptor.
	raw := map[string]any{}
	if err := yaml.Unmarshal([]byte(configText), &raw); err != nil {
		return nil, err
	}
	if raw == nil {
		raw = map[string]any{}
	}

	// Group selection/pinning is only restored across restarts when the config
	// opts in, so default it on unless the configuration says otherwise.
	if profile, ok := raw["profile"].(map[string]any); ok && profile != nil {
		if _, set := profile["store-selected"]; !set {
			profile["store-selected"] = true
		}
		if _, set := profile["store-fake-ip"]; !set {
			profile["store-fake-ip"] = true
		}
	} else {
		raw["profile"] = map[string]any{"store-selected": true, "store-fake-ip": true}
	}

	// Desktop-oriented keys that cannot work on Android: the OS reserves TCP/
	// UDP port 53 for system services, and unix controller paths point at the
	// desktop filesystem. TUN's dns-hijack already captures DNS traffic, so the
	// dns listener is redundant anyway.
	if dns, ok := raw["dns"].(map[string]any); ok && dns != nil {
		delete(dns, "listen")
	}
	delete(raw, "external-controller-unix")

	// tunnel.Proxies() marshals alphabetically; remember the declared group
	// order so the app can present groups as the config author wrote them.
	order := []string{}
	if groups, ok := raw["proxy-groups"].([]any); ok {
		for _, group := range groups {
			if entry, ok := group.(map[string]any); ok {
				if name, ok := entry["name"].(string); ok && name != "" {
					order = append(order, name)
				}
			}
		}
	}

	if tunFD >= 0 {
		tun, ok := raw["tun"].(map[string]any)
		if !ok || tun == nil {
			tun = map[string]any{}
		}
		tun["enable"] = true
		tun["device"] = "MikuBox"
		tun["file-descriptor"] = tunFD
		tun["auto-route"] = false
		tun["auto-detect-interface"] = false
		tun["strict-route"] = false
		// The stack stays whatever the profile (or the app override below)
		// selects; forcing one here silently discarded the profile's choice.
		raw["tun"] = tun
	} else {
		// Proxy mode is the non-VPN counterpart of UwU's ProxyService.
		raw["tun"] = map[string]any{"enable": false}
		if _, configured := raw["mixed-port"]; !configured {
			raw["mixed-port"] = 7890
		}
	}

	if dnsOverride != "" {
		dns := map[string]any{}
		if err := yaml.Unmarshal([]byte(dnsOverride), &dns); err != nil {
			return nil, err
		}
		raw["dns"] = dns
	}

	// App-level config overrides (log level, mode, allow-lan, tun stack). The
	// "tun-stack" key nests under the tun map built above; everything else is a
	// top-level replacement.
	if overridesJson != "" {
		overrides := map[string]any{}
		if err := json.Unmarshal([]byte(overridesJson), &overrides); err == nil {
			for key, value := range overrides {
				if key == "tun-stack" {
					if stack, ok := value.(string); ok && stack != "" {
						if tun, ok := raw["tun"].(map[string]any); ok {
							tun["stack"] = stack
						}
					}
					continue
				}
				if key == "tun-mtu" {
					if mtu, ok := value.(float64); ok && mtu > 0 {
						if tun, ok := raw["tun"].(map[string]any); ok {
							tun["mtu"] = int(mtu)
						}
					}
					continue
				}
				raw[key] = value
			}
		}
	}

	core.effective = effectiveSummary(raw)

	configBytes, err := yaml.Marshal(raw)
	if err != nil {
		return nil, err
	}
	return order, hub.Parse(configBytes)
}

// effectiveSummary extracts the non-sensitive runtime settings of the config
// the core is about to receive, for log-export diagnostics.
func effectiveSummary(raw map[string]any) map[string]any {
	summary := map[string]any{}
	if tunCfg, ok := raw["tun"].(map[string]any); ok {
		summary["tun_enable"] = tunCfg["enable"]
		summary["tun_stack"] = tunCfg["stack"]
		summary["tun_mtu"] = tunCfg["mtu"]
		if _, set := tunCfg["dns-hijack"]; set {
			summary["tun_dns_hijack"] = true
		}
	}
	if dnsCfg, ok := raw["dns"].(map[string]any); ok {
		summary["dns_enable"] = dnsCfg["enable"]
		summary["dns_mode"] = dnsCfg["enhanced-mode"]
		if listen, set := dnsCfg["listen"]; set {
			summary["dns_listen"] = listen
		}
	}
	summary["mode"] = raw["mode"]
	summary["mixed_port"] = raw["mixed-port"]
	summary["ipv6"] = raw["ipv6"]
	summary["allow_lan"] = raw["allow-lan"]
	if profile, ok := raw["profile"].(map[string]any); ok {
		summary["store_selected"] = profile["store-selected"]
		summary["store_fake_ip"] = profile["store-fake-ip"]
	}
	return summary
}

func main() {}
