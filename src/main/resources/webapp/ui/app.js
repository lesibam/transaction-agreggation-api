/*
 * Transact dashboard - vanilla JS, no build step (see ADR-013).
 *
 * The token is the only piece of client state that matters: everything else
 * is re-derived from API responses on demand. Authorization is enforced
 * server-side (SecurityConfig / CustomerAccessValidator) exactly as it is
 * for any other API caller - this UI has no privileges of its own, it is
 * just a browser for the same /v1/** contract documented at /docs.
 */
(() => {
  const TOKEN_KEY = "transact.token";
  const DEFAULT_LIMIT = 20;

  const el = (id) => document.getElementById(id);
  const gate = el("gate");
  const dashboard = el("dashboard");

  let state = {
    token: null,
    customerId: null,
    isAdmin: false,
    cursor: null,
    filters: {},
  };

  // ---------------------------------------------------------------- boot

  function boot() {
    const saved = sessionStorage.getItem(TOKEN_KEY);
    if (saved) {
      try {
        enterDashboard(saved);
        return;
      } catch {
        sessionStorage.removeItem(TOKEN_KEY);
      }
    }
    showGate();
  }

  function showGate(message) {
    gate.hidden = false;
    dashboard.hidden = true;
    const errorEl = el("gate-error");
    if (message) {
      errorEl.textContent = message;
      errorEl.hidden = false;
    } else {
      errorEl.hidden = true;
    }
  }

  function decodeJwt(token) {
    const parts = token.trim().split(".");
    if (parts.length !== 3) throw new Error("not a JWT");
    const payload = JSON.parse(
      atob(parts[1].replace(/-/g, "+").replace(/_/g, "/"))
    );
    if (!payload.sub) throw new Error("token has no sub claim");
    return payload;
  }

  function enterDashboard(token) {
    const payload = decodeJwt(token);
    state.token = token.trim();
    state.customerId = payload.sub;
    state.isAdmin = (payload.roles || []).includes("ADMIN");
    sessionStorage.setItem(TOKEN_KEY, state.token);

    gate.hidden = true;
    dashboard.hidden = false;

    el("customer-id-label").textContent = state.customerId;
    const badge = el("role-badge");
    badge.textContent = state.isAdmin ? "ADMIN" : "CUSTOMER";
    el("sources-tab-btn").hidden = !state.isAdmin;

    loadTrustStrip();
    loadTransactions({ reset: true });
  }

  el("gate-submit").addEventListener("click", () => {
    const raw = el("token-input").value;
    if (!raw.trim()) {
      showGate("Paste a token first.");
      return;
    }
    try {
      enterDashboard(raw);
    } catch {
      showGate("That doesn't look like a valid JWT.");
    }
  });

  el("sign-out").addEventListener("click", () => {
    sessionStorage.removeItem(TOKEN_KEY);
    state = { token: null, customerId: null, isAdmin: false, cursor: null, filters: {} };
    el("token-input").value = "";
    showGate();
  });

  // ---------------------------------------------------------------- fetch helper

  async function api(path) {
    const res = await fetch(path, {
      headers: { Authorization: `Bearer ${state.token}`, Accept: "application/json" },
    });
    const correlationId = res.headers.get("X-Correlation-ID");
    if (correlationId) {
      el("correlation-note").textContent = `last request: ${correlationId}`;
    }
    if (res.status === 401) {
      sessionStorage.removeItem(TOKEN_KEY);
      showGate("Session expired or invalid - sign in again.");
      throw new Error("unauthenticated");
    }
    if (!res.ok) {
      const problem = await res.json().catch(() => ({}));
      throw new Error(problem.detail || `request failed (${res.status})`);
    }
    return res.json();
  }

  // ---------------------------------------------------------------- trust strip

  function relativeTime(iso) {
    if (!iso) return "never";
    const seconds = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 1000));
    if (seconds < 60) return `${seconds}s ago`;
    if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
    if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
    return `${Math.floor(seconds / 86400)}d ago`;
  }

  function renderTrustStrip(meta) {
    const completeness = el("completeness-label");
    completeness.textContent = meta.completeness;
    completeness.dataset.state = meta.completeness === "COMPLETE" ? "complete" : "partial";

    const container = el("trust-sources");
    container.innerHTML = "";
    for (const source of meta.freshness.sources) {
      const chip = document.createElement("span");
      chip.className = "trust-chip";
      const status = deriveFreshnessDisplay(source);
      chip.innerHTML = `<span class="trust-dot" data-status="${status}"></span>${source.source} &middot; ${relativeTime(source.lastSync)}`;
      container.appendChild(chip);
    }
  }

  function deriveFreshnessDisplay(source) {
    // FreshnessSource only carries AVAILABLE/UNAVAILABLE (ADR-002/008); the
    // overall meta.freshness.status carries the FRESH/STALE/VERY_STALE/UNKNOWN
    // severity used elsewhere (e.g. /v1/admin/sources). Approximate per-chip
    // colour from what this endpoint actually gives us: UNAVAILABLE reads as
    // alert, AVAILABLE as fresh - good enough for an at-a-glance strip; the
    // Sources tab (admin) shows the real per-source freshnessStatus.
    return source.status === "AVAILABLE" ? "FRESH" : "UNAVAILABLE";
  }

  async function loadTrustStrip() {
    try {
      const data = await api(
        `/v1/customers/${encodeURIComponent(state.customerId)}/transactions?limit=1`
      );
      renderTrustStrip(data.meta);
    } catch {
      // Non-fatal - the transactions panel below will surface the real error.
    }
  }

  // ---------------------------------------------------------------- tabs

  document.querySelectorAll(".tab").forEach((btn) => {
    btn.addEventListener("click", () => switchTab(btn.dataset.tab));
  });

  function switchTab(name) {
    document.querySelectorAll(".tab").forEach((btn) => {
      btn.setAttribute("aria-selected", String(btn.dataset.tab === name));
    });
    document.querySelectorAll(".panel").forEach((panel) => {
      panel.hidden = panel.id !== `panel-${name}`;
    });
    if (name === "summary") loadSummary();
    if (name === "sources") loadSources();
  }

  // ---------------------------------------------------------------- transactions

  const filterForm = el("filter-form");
  filterForm.addEventListener("submit", (event) => {
    event.preventDefault();
    state.filters = {
      category: el("f-category").value.trim(),
      direction: el("f-direction").value,
      startDate: el("f-start").value,
      endDate: el("f-end").value,
      minAmount: el("f-min").value,
      maxAmount: el("f-max").value,
    };
    loadTransactions({ reset: true });
  });

  el("filter-reset").addEventListener("click", () => {
    filterForm.reset();
    state.filters = {};
    loadTransactions({ reset: true });
  });

  el("load-more").addEventListener("click", () => loadTransactions({ reset: false }));

  function buildTransactionsUrl() {
    const params = new URLSearchParams();
    params.set("limit", String(DEFAULT_LIMIT));
    if (state.cursor) params.set("cursor", state.cursor);
    for (const [key, value] of Object.entries(state.filters)) {
      if (value) params.set(key, value);
    }
    return `/v1/customers/${encodeURIComponent(state.customerId)}/transactions?${params}`;
  }

  function formatMoney(money) {
    try {
      return new Intl.NumberFormat("en-ZA", { style: "currency", currency: money.currency }).format(
        money.value
      );
    } catch {
      return `${money.value.toFixed(2)} ${money.currency}`;
    }
  }

  function renderTransactionRow(txn) {
    const tr = document.createElement("tr");
    const merchant = txn.merchant?.name || "—";
    tr.innerHTML = `
      <td class="mono">${txn.transactionDate.slice(0, 10)}</td>
      <td><span class="merchant-name">${escapeHtml(merchant)}</span>
        <span class="merchant-desc">${escapeHtml(txn.description || "")}</span></td>
      <td>${escapeHtml(txn.category?.code || "UNCATEGORIZED")}</td>
      <td class="source-id">${escapeHtml(txn.source.provider)}</td>
      <td class="num amount" data-direction="${txn.direction}">${formatMoney(txn.amount)}</td>
    `;
    return tr;
  }

  function escapeHtml(value) {
    const div = document.createElement("div");
    div.textContent = value;
    return div.innerHTML;
  }

  async function loadTransactions({ reset }) {
    const statusEl = el("txn-status");
    statusEl.hidden = true;
    if (reset) {
      state.cursor = null;
      el("txn-rows").innerHTML = "";
      el("txn-empty").hidden = true;
    }
    try {
      const data = await api(buildTransactionsUrl());
      const rows = el("txn-rows");
      for (const txn of data.data) rows.appendChild(renderTransactionRow(txn));
      state.cursor = data.meta.nextCursor;
      el("load-more").hidden = !data.meta.hasMore;
      el("txn-empty").hidden = rows.children.length > 0;
      renderTrustStrip(data.meta);
    } catch (err) {
      if (err.message !== "unauthenticated") {
        statusEl.textContent = err.message;
        statusEl.hidden = false;
      }
    }
  }

  // ---------------------------------------------------------------- summary

  function renderSummaryCards(summaries) {
    const container = el("summary-cards");
    container.innerHTML = "";
    if (summaries.length === 0) {
      container.innerHTML = '<p class="empty-state">No transactions to summarize yet.</p>';
      return;
    }
    for (const s of summaries) {
      const net = s.netFlow;
      const card = document.createElement("div");
      card.className = "summary-card";
      card.innerHTML = `
        <div class="currency">${s.currency}</div>
        <div class="net mono" data-sign="${net >= 0 ? "positive" : "negative"}">
          ${net >= 0 ? "+" : ""}${formatMoney({ value: net, currency: s.currency })}
        </div>
        <div class="breakdown-line"><span>Debits (${s.debitCount})</span>
          <span class="mono">${formatMoney({ value: s.totalDebit, currency: s.currency })}</span></div>
        <div class="breakdown-line"><span>Credits (${s.creditCount})</span>
          <span class="mono">${formatMoney({ value: s.totalCredit, currency: s.currency })}</span></div>
      `;
      container.appendChild(card);
    }
  }

  function renderCategoryBars(categories) {
    const container = el("category-bars");
    container.innerHTML = "";
    if (categories.length === 0) {
      container.innerHTML = '<p class="empty-state">Nothing categorized yet.</p>';
      return;
    }
    const max = Math.max(...categories.map((c) => c.amount));
    for (const c of categories) {
      const row = document.createElement("div");
      row.className = "category-bar-row";
      const pct = max > 0 ? Math.max(4, Math.round((c.amount / max) * 100)) : 0;
      row.innerHTML = `
        <span>${escapeHtml(c.category)}</span>
        <span class="category-bar-track"><span class="category-bar-fill" style="width:${pct}%"></span></span>
        <span class="mono">${formatMoney({ value: c.amount, currency: c.currency })}</span>
      `;
      container.appendChild(row);
    }
  }

  async function loadSummary() {
    try {
      const data = await api(`/v1/customers/${encodeURIComponent(state.customerId)}/summary`);
      renderSummaryCards(data.summaries);
      renderCategoryBars(data.categoryBreakdown);
    } catch {
      // 401 already redirected to the gate; anything else, leave panel as-is.
    }
  }

  // ---------------------------------------------------------------- sources (admin)

  async function loadSources() {
    try {
      const data = await api("/v1/admin/sources");
      const rows = el("sources-rows");
      rows.innerHTML = "";
      for (const s of data.sources) {
        const tr = document.createElement("tr");
        tr.innerHTML = `
          <td class="source-id">${escapeHtml(s.id)}</td>
          <td>${escapeHtml(s.status)}</td>
          <td class="mono">${relativeTime(s.lastSuccessfulSync)}</td>
          <td class="mono">${relativeTime(s.lastAttempt)}</td>
          <td class="num mono">${s.failureCount}</td>
          <td><span class="trust-dot" data-status="${s.freshnessStatus}"></span> ${s.freshnessStatus}</td>
        `;
        rows.appendChild(tr);
      }
    } catch {
      // 401 already redirected; ADMIN-gated 403 would only happen if a
      // non-admin somehow reached this tab, which the UI itself prevents.
    }
  }

  boot();
})();
