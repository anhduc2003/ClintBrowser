var __qgRanKey = __qgKey + 'r';
var __qgActiveKey = __qgKey + 'a';

function defineHidden(name, value) {
    try {
        Object.defineProperty(window, name, { value: value, configurable: true, enumerable: false, writable: false });
    } catch (e) {}
}

if (window[__qgRanKey] === true) return;
defineHidden(__qgRanKey, true);

var bridge = window.__qgBridge;
try { delete window.__qgBridge; } catch (e) {}
if (!bridge) return;

var topUrl = location.href;
try {
    var ancestors = location.ancestorOrigins;
    if (ancestors && ancestors.length) topUrl = ancestors[ancestors.length - 1];
} catch (e) {}

// The interaction guards below do not depend on a filter list matching this page, so they are
// installed before the cosmetic gate. The bridge returns an empty string when Quiver Guard is off
// or the site is excepted, which keeps the per-site exception working for them too.
var guardFlags = readGuardFlags(location.href, topUrl);
if (guardFlags) {
    defineHidden(__qgActiveKey, true);
    if (guardFlags.click) installClickHijackGuard();
    if (guardFlags.cookie) installCookieConsentDismissal();
}

var raw = '';
try { raw = bridge.cosmetic(location.href, topUrl); } catch (e) {}
if (!raw) return;

var data = null;
try { data = JSON.parse(raw); } catch (e) {}

if (data && data.injectedScript) {
    var scriptletRunner = null;
    try {
        scriptletRunner = new Function(data.injectedScript);
    } catch (e) {
        if (window === window.top) {
            try { bridge.scriptletFallback(location.href); } catch (e2) {}
        }
    }
    if (scriptletRunner) {
        try { scriptletRunner(); } catch (e) {}
    }
}

installWebSocketGuard();

if (!data || data.error) return;

var mutationListeners = [];
var mutationObserver = null;
var sheet = createSheet();

if (data.hideSelectors && data.hideSelectors.length) {
    sheet.add(data.hideSelectors, 'display:none!important');
}

if (data.proceduralActions && data.proceduralActions.length) {
    try { runProceduralActions(data.proceduralActions); } catch (e) {}
}

if (!data.genericHide) {
    try { runGenericHide(JSON.stringify(data.exceptions || [])); } catch (e) {}
}

onMutations(function () { sheet.ensure(); });

function onMutations(listener) {
    mutationListeners.push(listener);
    if (mutationObserver || typeof MutationObserver === 'undefined') return;
    mutationObserver = new MutationObserver(function (records) {
        for (var i = 0; i < mutationListeners.length; i++) {
            try { mutationListeners[i](records); } catch (e) {}
        }
    });
    try {
        mutationObserver.observe(document, {
            childList: true, subtree: true,
            attributes: true, attributeFilter: ['class', 'id']
        });
    } catch (e) {}
}

function createSheet() {
    var constructed = null;
    var styleElement = null;
    var pendingText = '';

    try {
        if ('adoptedStyleSheets' in document && typeof CSSStyleSheet === 'function') {
            constructed = new CSSStyleSheet();
            document.adoptedStyleSheets = Array.prototype.slice.call(document.adoptedStyleSheets).concat([constructed]);
        }
    } catch (e) {
        constructed = null;
    }

    function ensure() {
        if (constructed) {
            try {
                var current = document.adoptedStyleSheets;
                if (Array.prototype.indexOf.call(current, constructed) === -1) {
                    document.adoptedStyleSheets = Array.prototype.slice.call(current).concat([constructed]);
                }
            } catch (e) {}
            return;
        }
        if (!styleElement) {
            if (!pendingText) return;
            styleElement = document.createElement('style');
            styleElement.textContent = pendingText;
        }
        if (!styleElement.isConnected) {
            var parent = document.head || document.documentElement;
            if (parent) parent.appendChild(styleElement);
        }
    }

    function add(selectors, declaration) {
        if (constructed) {
            for (var i = 0; i < selectors.length; i++) {
                try {
                    constructed.insertRule(selectors[i] + '{' + declaration + '}', constructed.cssRules.length);
                } catch (e) {}
            }
            ensure();
            return;
        }
        var text = '';
        for (var j = 0; j < selectors.length; j++) {
            text += selectors[j] + '{' + declaration + '}\n';
        }
        pendingText += text;
        if (styleElement) styleElement.appendChild(document.createTextNode(text));
        ensure();
    }

    return { add: add, ensure: ensure };
}

function installWebSocketGuard() {
    var NativeWebSocket = window.WebSocket;
    if (typeof NativeWebSocket !== 'function' || typeof Proxy !== 'function' || typeof Reflect === 'undefined') return;
    var guarded = new Proxy(NativeWebSocket, {
        construct: function (target, args, newTarget) {
            try {
                if (args.length) {
                    var absolute = new URL(String(args[0]), location.href).href;
                    if (bridge.blockWebSocket(absolute, location.href) === true) {
                        var replaced = Array.prototype.slice.call(args);
                        replaced[0] = 'wss://websocket.blocked.invalid/';
                        return Reflect.construct(target, replaced, newTarget);
                    }
                }
            } catch (e) {}
            return Reflect.construct(target, args, newTarget);
        }
    });
    try { window.WebSocket = guarded; } catch (e) {}
}

function runProceduralActions(rawActions) {
    var rules = [];
    for (var i = 0; i < rawActions.length; i++) {
        try {
            var entry = rawActions[i];
            var rule = typeof entry === 'string' ? JSON.parse(entry) : entry;
            if (!rule || !Array.isArray(rule.selector) || !rule.selector.length) continue;
            var only = rule.selector.length === 1 ? rule.selector[0] : null;
            if (only && only.type === 'css-selector' && !rule.action) {
                sheet.add([only.arg], 'display:none!important');
            } else if (only && only.type === 'css-selector' && rule.action && rule.action.type === 'style') {
                sheet.add([only.arg], rule.action.arg);
            } else {
                rules.push(rule);
            }
        } catch (e) {}
    }
    if (!rules.length) return;

    function parseRegexArg(arg) {
        if (typeof arg !== 'string' || arg.length < 2 || arg.charAt(0) !== '/') return null;
        var lastSlash = arg.lastIndexOf('/');
        if (lastSlash <= 0) return null;
        try {
            return new RegExp(arg.slice(1, lastSlash), arg.slice(lastSlash + 1));
        } catch (e) {
            return null;
        }
    }

    function unquote(value) {
        var trimmed = value.trim();
        if (trimmed.length >= 2) {
            var first = trimmed.charAt(0);
            var last = trimmed.charAt(trimmed.length - 1);
            if ((first === '"' || first === "'") && first === last) return trimmed.slice(1, -1);
        }
        return trimmed;
    }

    function containsTextOrRegex(text, arg) {
        var re = parseRegexArg(arg);
        return re ? re.test(text) : text.indexOf(arg) !== -1;
    }

    function equalsTextOrRegex(text, arg) {
        var re = parseRegexArg(arg);
        return re ? re.test(text) : text === arg;
    }

    function splitAttrArg(arg) {
        var inRegex = false;
        var quote = '';
        for (var i = 0; i < arg.length; i++) {
            var c = arg.charAt(i);
            if (quote || inRegex) {
                if (c === '\\') { i++; continue; }
                if (quote && c === quote) quote = '';
                else if (inRegex && c === '/') inRegex = false;
                continue;
            }
            if (c === '"' || c === "'") { quote = c; continue; }
            if (c === '/' && i === 0) { inRegex = true; continue; }
            if (c === '=') return [arg.slice(0, i), arg.slice(i + 1)];
        }
        return [arg, null];
    }

    function matchesAttr(node, arg) {
        var parts = splitAttrArg(arg);
        var nameArg = unquote(parts[0]);
        var valueArg = parts[1] === null ? null : unquote(parts[1]);
        var nameRe = parseRegexArg(nameArg);
        var attrs = node.attributes;
        if (!attrs) return false;
        for (var i = 0; i < attrs.length; i++) {
            var attr = attrs[i];
            var nameMatches = nameRe ? nameRe.test(attr.name) : attr.name === nameArg;
            if (!nameMatches) continue;
            if (valueArg === null || equalsTextOrRegex(attr.value, valueArg)) return true;
        }
        return false;
    }

    function matchesCss(node, pseudo, arg) {
        var colon = arg.indexOf(':');
        if (colon === -1) return false;
        var prop = arg.slice(0, colon).trim();
        var value = arg.slice(colon + 1).trim();
        try {
            var style = pseudo ? getComputedStyle(node, pseudo) : getComputedStyle(node);
            return equalsTextOrRegex(style.getPropertyValue(prop).trim(), value);
        } catch (e) {
            return false;
        }
    }

    function upward(node, arg) {
        var trimmed = arg.trim();
        var count = parseInt(trimmed, 10);
        if (!isNaN(count) && String(count) === trimmed) {
            var cur = node;
            for (var i = 0; i < count && cur; i++) cur = cur.parentElement;
            return cur;
        }
        try {
            return node.parentElement ? node.parentElement.closest(trimmed) : null;
        } catch (e) {
            return null;
        }
    }

    function toArray(nodeList) {
        return Array.prototype.slice.call(nodeList);
    }

    function queryDocument(arg) {
        var selector = arg.replace(/^\s+/, '');
        if (/^[>+~]/.test(selector)) selector = ':root ' + selector;
        try { return toArray(document.querySelectorAll(selector)); } catch (e) { return []; }
    }

    function queryRelative(nodes, arg) {
        var seen = new Set();
        var out = [];
        nodes.forEach(function (node) {
            var scope;
            var selector;
            var parent = node.parentElement;
            if (parent) {
                var index = 1;
                var sibling = node;
                while ((sibling = sibling.previousElementSibling)) index++;
                scope = parent;
                selector = ':scope > :nth-child(' + index + ')' + arg;
            } else {
                scope = document;
                selector = ':root' + arg;
            }
            try {
                var found = scope.querySelectorAll(selector);
                for (var i = 0; i < found.length; i++) {
                    if (!seen.has(found[i])) {
                        seen.add(found[i]);
                        out.push(found[i]);
                    }
                }
            } catch (e) {}
        });
        return out;
    }

    function applyOperator(op, nodes) {
        var type = op.type, arg = op.arg;

        if (type === 'css-selector') {
            return nodes === null ? queryDocument(arg) : queryRelative(nodes, arg);
        }

        if (type === 'xpath') {
            var out = [];
            var contexts = nodes === null ? [document] : nodes;
            contexts.forEach(function (ctx) {
                try {
                    var result = document.evaluate(arg, ctx, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
                    for (var j = 0; j < result.snapshotLength; j++) {
                        var item = result.snapshotItem(j);
                        if (item && item.nodeType === 1 && out.indexOf(item) === -1) out.push(item);
                    }
                } catch (e) {}
            });
            return out;
        }

        if (type === 'matches-path') {
            return containsTextOrRegex(location.pathname + location.search, arg) ? nodes : [];
        }

        if (nodes === null) return [];
        switch (type) {
            case 'has-text':
                return nodes.filter(function (n) { return containsTextOrRegex(n.textContent || '', arg); });
            case 'matches-attr':
                return nodes.filter(function (n) { return matchesAttr(n, arg); });
            case 'matches-css':
                return nodes.filter(function (n) { return matchesCss(n, null, arg); });
            case 'matches-css-before':
                return nodes.filter(function (n) { return matchesCss(n, '::before', arg); });
            case 'matches-css-after':
                return nodes.filter(function (n) { return matchesCss(n, '::after', arg); });
            case 'min-text-length':
                var min = parseInt(arg, 10) || 0;
                return nodes.filter(function (n) { return (n.textContent || '').length >= min; });
            case 'upward':
                var mapped = [];
                nodes.forEach(function (n) {
                    var a = upward(n, arg);
                    if (a && mapped.indexOf(a) === -1) mapped.push(a);
                });
                return mapped;
            default:
                return [];
        }
    }

    function evaluateRule(rule) {
        var nodes = null;
        for (var i = 0; i < rule.selector.length; i++) {
            nodes = applyOperator(rule.selector[i], nodes);
            if (nodes !== null && !nodes.length) return [];
        }
        return nodes || [];
    }

    function applyAction(nodes, action) {
        if (!action) {
            nodes.forEach(function (n) {
                if (n.style) n.style.setProperty('display', 'none', 'important');
            });
            return;
        }
        switch (action.type) {
            case 'remove':
                nodes.forEach(function (n) { n.remove(); });
                break;
            case 'style':
                nodes.forEach(function (n) { if (n.style) n.style.cssText += ';' + action.arg; });
                break;
            case 'remove-attr':
                nodes.forEach(function (n) { n.removeAttribute(action.arg); });
                break;
            case 'remove-class':
                nodes.forEach(function (n) { if (n.classList) n.classList.remove(action.arg); });
                break;
        }
    }

    function runOnce() {
        rules.forEach(function (rule) {
            try {
                var nodes = evaluateRule(rule);
                if (nodes.length) applyAction(nodes, rule.action);
            } catch (e) {}
        });
    }

    runOnce();

    var pending = false;
    onMutations(function () {
        if (pending) return;
        pending = true;
        setTimeout(function () { pending = false; runOnce(); }, 100);
    });
}

function runGenericHide(exceptionsJson) {
    var seenClasses = Object.create(null);
    var seenIds = Object.create(null);
    var pendingClasses = [];
    var pendingIds = [];
    var scheduled = false;

    function collect(el) {
        if (!el || el.nodeType !== 1) return;
        var id = el.id;
        if (id && typeof id === 'string' && !seenIds[id]) {
            seenIds[id] = true;
            pendingIds.push(id);
        }
        var classList = el.classList;
        if (classList) {
            for (var i = 0; i < classList.length; i++) {
                var c = classList[i];
                if (c && !seenClasses[c]) {
                    seenClasses[c] = true;
                    pendingClasses.push(c);
                }
            }
        }
    }

    function collectTree(node) {
        if (!node || node.nodeType !== 1) return;
        collect(node);
        if (!node.querySelectorAll) return;
        var all = node.querySelectorAll('[class],[id]');
        for (var i = 0; i < all.length; i++) collect(all[i]);
    }

    function flush() {
        scheduled = false;
        if (!pendingClasses.length && !pendingIds.length) return;
        var classes = pendingClasses;
        var ids = pendingIds;
        pendingClasses = [];
        pendingIds = [];
        try {
            var selectors = JSON.parse(bridge.hiddenClassIdSelectors(
                JSON.stringify(classes), JSON.stringify(ids), exceptionsJson
            ));
            if (selectors && selectors.length) sheet.add(selectors, 'display:none!important');
        } catch (e) {}
    }

    function schedule() {
        if (scheduled) return;
        scheduled = true;
        setTimeout(flush, 50);
    }

    collectTree(document.documentElement);
    flush();

    onMutations(function (records) {
        for (var i = 0; i < records.length; i++) {
            var record = records[i];
            if (record.type === 'attributes') {
                collect(record.target);
            } else {
                var added = record.addedNodes;
                for (var j = 0; j < added.length; j++) collectTree(added[j]);
            }
        }
        if (pendingClasses.length || pendingIds.length) schedule();
    });
}

// Interaction guards: filter lists cover the network and element hiding, but they cannot see a
// transparent anchor laid over the page or a consent banner the site renders itself. Both run
// inside the same "active for this page" gate as the cosmetic filtering above, and each one is
// switched on or off from the Quiver Guard settings, so a site that breaks under them can be
// handled with the existing per-site exception.

// Returns null when Quiver Guard is off or excepted for this page, so the caller can skip both
// guards entirely; otherwise the per-guard switches with the settings' own defaults.
function readGuardFlags(url, topUrl) {
    try {
        var raw = bridge.guardFlags(url, topUrl);
        if (!raw) return null;
        var parsed = JSON.parse(raw);
        if (parsed && typeof parsed === 'object') {
            return { cookie: parsed.cookie !== false, click: parsed.click !== false };
        }
    } catch (e) {}
    return null;
}

function registrableDomain(hostname) {
    if (!hostname) return '';
    var host = String(hostname).toLowerCase();
    var parts = host.split('.');
    if (parts.length <= 2) return host;
    var twoLevel = {
        'co.uk': 1, 'com.au': 1, 'co.jp': 1, 'co.kr': 1, 'com.br': 1, 'co.in': 1,
        'com.vn': 1, 'com.sg': 1, 'com.my': 1, 'com.ph': 1, 'com.tw': 1, 'com.hk': 1,
        'co.nz': 1, 'co.za': 1, 'com.mx': 1, 'com.ar': 1, 'com.tr': 1, 'com.ua': 1
    };
    var suffix = parts.slice(-2).join('.');
    if (twoLevel[suffix]) return parts.slice(-3).join('.');
    return suffix;
}

function installClickHijackGuard() {
    var installedKey = '__qgClickGuardInstalled';
    if (window[installedKey] === true) return;
    defineHidden(installedKey, true);

    var MIN_AREA_RATIO = 0.6;

    function pageDomain() {
        try { return registrableDomain(location.hostname); } catch (e) { return ''; }
    }

    function hasVisibleContent(el) {
        if ((el.textContent || '').trim().length > 3) return true;
        try {
            if (el.querySelector('img, picture, svg, video, canvas, iframe, object, embed')) return true;
        } catch (e) {}
        return false;
    }

    function isHijack(anchor) {
        var target;
        try { target = new URL(anchor.getAttribute('href'), document.baseURI); } catch (e) { return false; }
        if (target.protocol !== 'http:' && target.protocol !== 'https:') return false;
        var targetDomain = registrableDomain(target.hostname);
        var page = pageDomain();
        if (!targetDomain || !page || targetDomain === page) return false;

        var rect = anchor.getBoundingClientRect();
        var vw = window.innerWidth || document.documentElement.clientWidth || 0;
        var vh = window.innerHeight || document.documentElement.clientHeight || 0;
        if (!vw || !vh || rect.width <= 0 || rect.height <= 0) return false;
        if ((rect.width * rect.height) / (vw * vh) < MIN_AREA_RATIO) return false;

        var style;
        try { style = getComputedStyle(anchor); } catch (e) { return false; }
        var positioned = style.position === 'fixed' || style.position === 'absolute';
        if (!positioned) return false;

        var opacity = parseFloat(style.opacity);
        if (!isNaN(opacity) && opacity <= 0.05) return true;
        if (style.visibility === 'hidden') return true;

        var fullWidth = rect.width >= vw * 0.9;
        var fullHeight = rect.height >= vh * 0.6;
        return fullWidth && fullHeight && !hasVisibleContent(anchor);
    }

    // Popunders are the other half of a click hijack: a handler opens a tab the person never
    // asked for. A gesture on a real control earns one popup; anything else is dropped.
    var POPUP_GESTURE_WINDOW_MS = 2000;
    var lastGesture = null;
    function markGesture(event) {
        if (!event.isTrusted) return;
        var target = event.target;
        lastGesture = {
            at: Date.now(),
            target: target && target.nodeType === 1 ? target : null
        };
    }
    document.addEventListener('pointerdown', markGesture, true);
    document.addEventListener('keydown', markGesture, true);

    function gestureAllowsPopup() {
        if (!lastGesture) return false;
        if (Date.now() - lastGesture.at > POPUP_GESTURE_WINDOW_MS) return false;
        var target = lastGesture.target;
        if (!target || !target.closest) return false;
        var control;
        try {
            control = target.closest('a[href], button, [role="button"], input[type="submit"], input[type="button"], label');
        } catch (e) {
            return false;
        }
        if (!control) return false;
        var style = window.getComputedStyle(control);
        if (parseFloat(style.opacity) <= 0.05) return false;
        var rect = control.getBoundingClientRect();
        return rect.width >= 4 && rect.height >= 4;
    }

    var nativeOpen = window.open;
    if (typeof nativeOpen === 'function') {
        window.open = function (url) {
            if (!gestureAllowsPopup()) {
                try { bridge.reportBlockedClick(String(url || 'popup')); } catch (e) {}
                return null;
            }
            lastGesture = null;
            return nativeOpen.apply(window, arguments);
        };
    }

    document.addEventListener('click', function (event) {
        if (!event.isTrusted) return;
        var node = event.target;
        var anchor = null;
        while (node && node !== document) {
            if (node.nodeType === 1 && node.tagName === 'A' && node.hasAttribute('href')) {
                anchor = node;
                break;
            }
            node = node.parentElement;
        }
        if (!anchor) return;
        var hijack = false;
        try { hijack = isHijack(anchor); } catch (e) {}
        if (!hijack) return;
        try { event.preventDefault(); } catch (e) {}
        try { event.stopImmediatePropagation(); } catch (e) {}
        try { bridge.reportBlockedClick(anchor.href); } catch (e) {}
    }, true);
}

function installCookieConsentDismissal() {
    var installedKey = '__qgConsentGuardInstalled';
    if (window[installedKey] === true) return;
    defineHidden(installedKey, true);

    var CONSENT_TEXT = /(cookie|consent|gdpr|ccpa|privacy (policy|settings|preferences|choices)|we (use|value)|our partners|legitimate interest|tracking technologies|đồng ý|chấp thuận|sự đồng ý|quyền riêng tư|bánh quy)/i;
    var REJECT_TEXT = /^(reject|reject all|decline|decline all|refuse|refuse all|disagree|deny|no thanks|not now|only (necessary|essential|required)|essential only|necessary only|use necessary|continue without accepting|từ chối|từ chối tất cả|không đồng ý|chỉ (cần thiết|thiết yếu))/i;
    var ACCEPT_TEXT = /^(accept|accept all|allow|allow all|agree|agree to all|i agree|ok|okay|got it|consent|đồng ý|chấp nhận|chấp thuận|đồng ý tất cả)/i;
    var CANDIDATE_SELECTOR = [
        '[id*="cookie" i]', '[class*="cookie" i]', '[id*="consent" i]', '[class*="consent" i]',
        '[id*="gdpr" i]', '[class*="gdpr" i]', '[id*="cmp" i]', '[class*="cmp" i]',
        '[aria-label*="cookie" i]', '[aria-label*="consent" i]', '[role="dialog"]', '[role="alertdialog"]'
    ].join(',');

    var MAX_ATTEMPTS = 14;
    var attempts = 0;
    var finished = false;
    var scheduled = false;
    var observer = null;

    function isVisible(el, minWidth, minHeight) {
        if (!el || el.nodeType !== 1) return false;
        var rect = el.getBoundingClientRect();
        if (rect.width < (minWidth || 120) || rect.height < (minHeight || 32)) return false;
        var style;
        try { style = getComputedStyle(el); } catch (e) { return false; }
        if (style.display === 'none' || style.visibility === 'hidden') return false;
        var opacity = parseFloat(style.opacity);
        return isNaN(opacity) || opacity > 0.05;
    }

    function isBannerLike(el) {
        try {
            var style = getComputedStyle(el);
            if (style.position === 'fixed' || style.position === 'sticky') return true;
        } catch (e) {}
        var role = el.getAttribute('role');
        if (role === 'dialog' || role === 'alertdialog') return true;
        return false;
    }

    function labelOf(el) {
        var text = (el.getAttribute('aria-label') || el.textContent || el.value || '').trim();
        return text.replace(/\s+/g, ' ').slice(0, 80);
    }

    function findButton(container, pattern) {
        var nodes = container.querySelectorAll('button, [role="button"], a[href], input[type="button"], input[type="submit"]');
        for (var i = 0; i < nodes.length && i < 40; i++) {
            var node = nodes[i];
            if (!isVisible(node, 16, 12)) continue;
            var label = labelOf(node);
            if (label && pattern.test(label)) return node;
        }
        return null;
    }

    function clickElement(el) {
        var rect = el.getBoundingClientRect();
        var opts = {
            bubbles: true, cancelable: true, view: window,
            clientX: rect.left + rect.width / 2, clientY: rect.top + rect.height / 2
        };
        try {
            el.dispatchEvent(new MouseEvent('pointerdown', opts));
            el.dispatchEvent(new MouseEvent('mousedown', opts));
            el.dispatchEvent(new MouseEvent('mouseup', opts));
            el.dispatchEvent(new MouseEvent('click', opts));
            return true;
        } catch (e) {}
        try { el.click(); return true; } catch (e2) {}
        return false;
    }

    function hideElement(el) {
        try { el.style.setProperty('display', 'none', 'important'); } catch (e) {}
    }

    function dismiss(container) {
        var button = findButton(container, REJECT_TEXT);
        if (!button) button = findButton(container, ACCEPT_TEXT);
        if (button) {
            clickElement(button);
            setTimeout(function () {
                if (isVisible(container)) hideElement(container);
            }, 700);
            return true;
        }
        // No recognisable choice button. Only hide a bar that covers part of the viewport, never a
        // full-screen dialog the person still needs to interact with.
        if (!isBannerLike(container)) return false;
        var rect = container.getBoundingClientRect();
        var vw = window.innerWidth || 0;
        var vh = window.innerHeight || 0;
        if (!vw || !vh || rect.width * rect.height > vw * vh * 0.5) return false;
        hideElement(container);
        return true;
    }

    function scan() {
        if (finished || attempts >= MAX_ATTEMPTS) return;
        attempts++;
        var found = false;
        var candidates;
        try { candidates = document.querySelectorAll(CANDIDATE_SELECTOR); } catch (e) { return; }
        var limit = Math.min(candidates.length, 60);
        for (var i = 0; i < limit; i++) {
            var el = candidates[i];
            if (!el || el.nodeType !== 1) continue;
            if (el.getAttribute('data-qg-consent') === 'done') continue;
            if (!isVisible(el)) continue;
            var text = (el.textContent || '').trim();
            if (text.length < 20 || text.length > 4000) continue;
            if (!CONSENT_TEXT.test(text)) continue;
            try { el.setAttribute('data-qg-consent', 'done'); } catch (e) {}
            var dismissed = false;
            try { dismissed = dismiss(el); } catch (e) {}
            if (dismissed) found = true;
        }
        if (found) {
            finished = true;
            if (observer) {
                try { observer.disconnect(); } catch (e) {}
            }
            return;
        }
        schedule();
    }

    function schedule() {
        if (scheduled || finished || attempts >= MAX_ATTEMPTS) return;
        scheduled = true;
        setTimeout(function () {
            scheduled = false;
            scan();
        }, 1200);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', scan, { once: true });
    } else {
        scan();
    }

    try {
        if (typeof MutationObserver === 'function') {
            observer = new MutationObserver(function () {
                if (!finished) schedule();
            });
            observer.observe(document, { childList: true, subtree: true });
        }
    } catch (e) {}
}
