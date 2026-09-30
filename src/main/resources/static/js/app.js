/* Progressive enhancement only: the page is fully server-rendered and readable without JS. */
(function () {
    'use strict';
    const $$ = (selector, root) => Array.from((root || document).querySelectorAll(selector));
    const onActivate = (el, fn) => {
        el.addEventListener('click', fn);
        el.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fn(e); }
        });
    };

    // Quick-pick symbols submit the form with the current parameters.
    const symbolInput = document.getElementById('symbol');
    $$('.chip[data-symbol]').forEach((chip) => chip.addEventListener('click', () => {
        symbolInput.value = chip.dataset.symbol;
        const form = symbolInput.form;
        if (form.requestSubmit) form.requestSubmit(); else form.submit();
    }));
    if (symbolInput) symbolInput.addEventListener('input', () => {    $$('.exp-row').forEach((row) => onActivate(row, () => setGroup(row, row.getAttribute('aria-expanded') !== 'true')));
        symbolInput.value = symbolInput.value.toUpperCase();
    });

    // Expiration groups: expand / collapse.
    function setGroup(row, open) {
        const body = document.getElementById('group-' + row.dataset.group);
        if (!body) return;
        body.hidden = !open;
        row.setAttribute('aria-expanded', String(open));
    }
    $$('.exp-row').forEach((row) => onActivate(row, () => {
        const open = row.getAttribute('aria-expanded') !== 'true';
        if (open) $$('.exp-row').forEach((r) => { if (r !== row) setGroup(r, false); });
        setGroup(row, open);
    }));    $$('[data-expand]').forEach((btn) => btn.addEventListener('click', () => {
        const open = btn.dataset.expand === 'all';
        $$('.exp-row').forEach((row) => setGroup(row, open));
    }));

    // Sortable tables: th[data-sort="num|text"], cells carry data-v with the raw value.
    $$('table.sortable').forEach((table) => {
        const headers = $$('thead th[data-sort]', table);
        headers.forEach((th) => {
            th.tabIndex = 0;
            th.setAttribute('aria-sort', 'none');
            onActivate(th, () => {
                const body = table.tBodies[0];
                if (!body) return;
                const ascending = th.getAttribute('aria-sort') !== 'ascending';
                headers.forEach((h) => h.setAttribute('aria-sort', 'none'));
                th.setAttribute('aria-sort', ascending ? 'ascending' : 'descending');
                const index = th.cellIndex;
                const numeric = th.dataset.sort === 'num';
                const value = (row) => {
                    const cell = row.cells[index];
                    const raw = cell.dataset.v !== undefined ? cell.dataset.v : cell.textContent.trim();
                    return numeric ? parseFloat(raw) : raw;
                };
                const rows = Array.from(body.rows);
                rows.sort((a, b) => {
                    const va = value(a), vb = value(b);
                    if (numeric) {
                        const aBad = Number.isNaN(va), bBad = Number.isNaN(vb);
                        if (aBad || bBad) return aBad === bBad ? 0 : (aBad ? 1 : -1); // missing values last
                        return ascending ? va - vb : vb - va;
                    }
                    return ascending ? String(va).localeCompare(String(vb)) : String(vb).localeCompare(String(va));
                });
                rows.forEach((r) => body.appendChild(r));
            });
        });
    });

    // Candidate selection: show its details and outline its legs in the chain.
    const cssEscape = (s) => (window.CSS && CSS.escape ? CSS.escape(s) : s.replace(/"/g, '\\"'));
    function selectCandidate(row) {
        $$('tr.cand.selected').forEach((r) => { r.classList.remove('selected'); r.removeAttribute('aria-selected'); });
        row.classList.add('selected');
        row.setAttribute('aria-selected', 'true');
        $$('article.detail').forEach((d) => { d.hidden = d.id !== 'cand-' + row.dataset.id; });

        $$('tr.leg-put, tr.leg-call').forEach((r) => r.classList.remove('leg-put', 'leg-call'));
        const putRow = document.querySelector('tr.strike-row[data-put="' + cssEscape(row.dataset.put) + '"]');
        const callRow = document.querySelector('tr.strike-row[data-call="' + cssEscape(row.dataset.call) + '"]');
        if (putRow) putRow.classList.add('leg-put');
        if (callRow) callRow.classList.add('leg-call');
        [putRow, callRow].forEach((r) => {
            const body = r && r.closest('tbody.exp-body');
            if (body && body.hidden) {
                const head = document.querySelector('.exp-row[aria-controls="' + body.id + '"]');
                if (head) $$('.exp-row').forEach((r) => setGroup(r, r === head));            }
        });
    }
    const candidates = $$('tr.cand');
    candidates.forEach((row) => onActivate(row, () => selectCandidate(row)));
    if (candidates.length) selectCandidate(candidates[0]);
})();
