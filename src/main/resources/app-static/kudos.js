/*
 * Copyright 2026 Aleksey Martynov and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Screen behaviour for the kudos pages. Every call goes to this
 * application's own Spring API under the browser's authenticated session.
 */
(function () {
  'use strict';

  var UI_API = '/ui-api';

  function el(id) {
    return document.getElementById(id);
  }

  function text(value) {
    return document.createTextNode(value === null || value === undefined ? '' : String(value));
  }

  function showError(container, error) {
    container.innerHTML = '';
    var box = document.createElement('div');
    box.className = 'k8s-error';
    box.appendChild(text(error && error.message ? error.message : String(error)));
    container.appendChild(box);
  }

  function browserFetch(url, options) {
    var configured = Object.assign({ credentials: 'same-origin' }, options || {});
    var method = (configured.method || 'GET').toUpperCase();
    if (['GET', 'HEAD', 'OPTIONS', 'TRACE'].indexOf(method) === -1) {
      var token = document.querySelector('meta[name="_csrf"]');
      var header = document.querySelector('meta[name="_csrf_header"]');
      if (token && header) {
        configured.headers = Object.assign({}, configured.headers || {});
        configured.headers[header.getAttribute('content')] = token.getAttribute('content');
      }
    }
    return fetch(url, configured);
  }

  function request(url, options) {
    return browserFetch(url, options).then(
      function (response) {
        if (!response.ok) {
          return response.text().then(function (body) {
            var message = 'HTTP ' + response.status;
            try {
              var parsed = JSON.parse(body);
              if (parsed.message) {
                message = parsed.message;
              }
            } catch (ignored) {
              if (body) {
                message = body;
              }
            }
            throw new Error(message);
          });
        }
        var type = response.headers.get('content-type') || '';
        return type.indexOf('application/json') === 0 ? response.json() : response.text();
      }
    );
  }

  function formatSize(bytes) {
    if (bytes === 0) {
      return '0 B';
    }
    var units = ['B', 'KB', 'MB', 'GB', 'TB'];
    var index = Math.floor(Math.log(bytes) / Math.log(1024));
    index = Math.min(index, units.length - 1);
    return (bytes / Math.pow(1024, index)).toFixed(index === 0 ? 0 : 1) + ' ' + units[index];
  }

  function formatDate(millis) {
    if (!millis) {
      return '';
    }
    return new Date(millis).toISOString().replace('T', ' ').substring(0, 19);
  }

  function buildTable(columns, rows, cellRenderer) {
    var table = document.createElement('table');
    table.className = 'table table-condensed table-huedatatable';
    var thead = document.createElement('thead');
    var headRow = document.createElement('tr');
    columns.forEach(function (column) {
      var th = document.createElement('th');
      th.appendChild(text(column));
      headRow.appendChild(th);
    });
    thead.appendChild(headRow);
    table.appendChild(thead);

    var tbody = document.createElement('tbody');
    rows.forEach(function (row) {
      var tr = document.createElement('tr');
      (cellRenderer ? cellRenderer(row) : row).forEach(function (cell) {
        var td = document.createElement('td');
        if (cell instanceof Node) {
          td.appendChild(cell);
        } else {
          td.appendChild(text(cell));
        }
        tr.appendChild(td);
      });
      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    return table;
  }

  /* ----------------------------------------------------------- dom helpers */

  // Tiny element builder: element('input', { type: 'text', value: x }, [child]).
  function element(tag, attrs, children) {
    var node = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (key) {
        if (key === 'class') {
          node.className = attrs[key];
        } else if (key === 'text') {
          node.appendChild(text(attrs[key]));
        } else if (key in node) {
          node[key] = attrs[key];
        } else {
          node.setAttribute(key, attrs[key]);
        }
      });
    }
    (children || []).forEach(function (child) {
      if (child == null) {
        return;
      }
      node.appendChild(child instanceof Node ? child : text(child));
    });
    return node;
  }

  function button(label, className, onClick) {
    var btn = element('button', { type: 'button', class: className }, [label]);
    btn.addEventListener('click', onClick);
    return btn;
  }

  function field(labelText, control) {
    return element('div', { class: 'k8s-field' }, [
      element('label', { text: labelText }),
      control
    ]);
  }

  // POST JSON and resolve to the parsed body (or nothing for an empty 200).
  function send(url, body) {
    return request(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body || {})
    });
  }

  /* ------------------------------------------------------------------ modal */

  // A single reusable modal. openModal returns a handle whose close() removes it.
  function openModal(title, bodyNode, buttons) {
    var backdrop = element('div', { class: 'k8s-modal-backdrop' });
    var footer = element('div', { class: 'k8s-modal-footer' });
    var handle = {
      buttons: [],
      close: function () {
        if (backdrop.parentNode) {
          backdrop.parentNode.removeChild(backdrop);
        }
        document.removeEventListener('keydown', onKey);
      }
    };
    (buttons || []).forEach(function (spec) {
      var btn = button(spec.label, spec.class || 'btn btn-small', function () {
        spec.action(handle);
      });
      handle.buttons.push(btn);
      footer.appendChild(btn);
    });
    var dialog = element('div', { class: 'k8s-modal' }, [
      element('div', { class: 'k8s-modal-header' }, [
        element('span', { text: title }),
        button('×', 'k8s-modal-close', handle.close)
      ]),
      element('div', { class: 'k8s-modal-body' }, [bodyNode]),
      footer
    ]);
    backdrop.appendChild(dialog);
    backdrop.addEventListener('click', function (event) {
      if (event.target === backdrop) {
        handle.close();
      }
    });
    function onKey(event) {
      if (event.key === 'Escape') {
        handle.close();
      }
    }
    document.addEventListener('keydown', onKey);
    document.body.appendChild(backdrop);
    return handle;
  }

  function confirmModal(message, onConfirm) {
    openModal('Confirm', element('div', { class: 'k8s-modal-message', text: message }), [
      { label: 'Cancel', action: function (h) { h.close(); } },
      {
        label: 'OK',
        class: 'btn btn-small btn-danger',
        action: function (h) {
          h.close();
          onConfirm();
        }
      }
    ]);
  }

  // A destructive action guarded by a typed confirmation: the button stays
  // disabled until the user types the exact keyword, so a stray click cannot
  // drop a table or delete a row.
  function confirmDestructive(message, keyword, onConfirm) {
    var input = textInput('', keyword);
    var body = element('div', {}, [
      element('div', { class: 'k8s-modal-message', text: message }),
      element('div', { class: 'k8s-muted', text: 'Type "' + keyword + '" to confirm.' }),
      field(keyword, input)
    ]);
    var handle = openModal('Confirm', body, [
      { label: 'Cancel', action: function (h) { h.close(); } },
      {
        label: 'Confirm',
        class: 'btn btn-small btn-danger',
        action: function (h) {
          if (input.value.trim() !== keyword) {
            input.focus();
            return;
          }
          h.close();
          onConfirm();
        }
      }
    ]);
    var confirmBtn = handle.buttons[handle.buttons.length - 1];
    confirmBtn.disabled = true;
    input.addEventListener('input', function () {
      confirmBtn.disabled = input.value.trim() !== keyword;
    });
    input.addEventListener('keydown', function (event) {
      if (event.key === 'Enter' && input.value.trim() === keyword) {
        handle.close();
        onConfirm();
      }
    });
    setTimeout(function () {
      input.focus();
    }, 0);
    return handle;
  }

  /* ------------------------------------------------------------------ editor */

  // Spark SQL vocabulary for the Ace autocompleter. Keywords (incl. Spark-only
  // clauses like LATERAL VIEW / DISTRIBUTE BY) and built-in functions.
  var SPARK_SQL_KEYWORDS = (
    'SELECT FROM WHERE GROUP BY ORDER BY HAVING LIMIT OFFSET DISTINCT ALL AS AND OR NOT ' +
    'IN EXISTS BETWEEN LIKE RLIKE REGEXP IS NULL TRUE FALSE CASE WHEN THEN ELSE END ' +
    'JOIN INNER JOIN LEFT JOIN RIGHT JOIN FULL JOIN LEFT OUTER JOIN RIGHT OUTER JOIN ' +
    'FULL OUTER JOIN CROSS JOIN LEFT SEMI JOIN LEFT ANTI JOIN ON USING NATURAL ' +
    'UNION UNION ALL INTERSECT EXCEPT MINUS WITH RECURSIVE VALUES ' +
    'INSERT INTO INSERT OVERWRITE OVERWRITE TABLE CREATE TABLE CREATE OR REPLACE ' +
    'CREATE VIEW CREATE TEMPORARY VIEW DROP TABLE DROP VIEW ALTER TABLE TRUNCATE TABLE ' +
    'DESCRIBE DESC EXTENDED FORMATTED SHOW TABLES SHOW DATABASES SHOW COLUMNS SHOW ' +
    'PARTITIONS USE EXPLAIN CACHE TABLE UNCACHE TABLE REFRESH TABLE ANALYZE TABLE ' +
    'COMPUTE STATISTICS PARTITION PARTITIONED BY CLUSTERED BY SORTED BY INTO BUCKETS ' +
    'DISTRIBUTE BY SORT BY CLUSTER BY LATERAL VIEW OUTER OVER PARTITION BY WINDOW ' +
    'ROWS BETWEEN RANGE BETWEEN UNBOUNDED PRECEDING CURRENT ROW FOLLOWING TABLESAMPLE ' +
    'PIVOT UNPIVOT GROUPING SETS ROLLUP CUBE CAST TRY_CAST INTERVAL ARRAY MAP STRUCT ' +
    'STORED AS ROW FORMAT DELIMITED LOCATION TBLPROPERTIES OPTIONS COMMENT ' +
    'IF NOT EXISTS IF EXISTS ADD COLUMNS RENAME TO SET RESET ASC DESC NULLS FIRST ' +
    'NULLS LAST FETCH FIRST NEXT ONLY QUALIFY'
  ).split(' ');
  var SPARK_SQL_FUNCTIONS = (
    'count sum avg min max first last first_value last_value nth_value collect_list ' +
    'collect_set approx_count_distinct stddev stddev_pop stddev_samp variance var_pop ' +
    'var_samp skewness kurtosis corr covar_pop covar_samp percentile percentile_approx ' +
    'coalesce nvl nvl2 nullif greatest least ifnull isnull isnotnull nanvl if ' +
    'concat concat_ws substring substr left right length char_length lower upper trim ' +
    'ltrim rtrim lpad rpad repeat reverse replace overlay format_string format_number ' +
    'regexp_replace regexp_extract regexp_extract_all split split_part instr locate ' +
    'position ascii chr initcap translate soundex levenshtein base64 unbase64 ' +
    'current_date current_timestamp now date_add date_sub datediff months_between ' +
    'add_months last_day next_day trunc date_trunc extract year month day dayofmonth ' +
    'dayofweek dayofyear weekday hour minute second weekofyear quarter make_date ' +
    'to_date to_timestamp from_unixtime unix_timestamp to_unix_timestamp date_format ' +
    'from_utc_timestamp to_utc_timestamp timestampadd timestampdiff ' +
    'abs ceil ceiling floor round bround sqrt cbrt exp expm1 ln log log10 log2 pow ' +
    'power rand randn pmod mod sign signum factorial hypot degrees radians ' +
    'row_number rank dense_rank percent_rank ntile lag lead cume_dist ' +
    'explode explode_outer posexplode posexplode_outer inline inline_outer stack ' +
    'array map struct named_struct array_contains array_position size cardinality ' +
    'sort_array array_distinct array_union array_intersect array_except array_join ' +
    'arrays_zip flatten sequence shuffle slice element_at map_keys map_values ' +
    'map_entries map_from_arrays map_concat get_json_object json_tuple from_json ' +
    'to_json schema_of_json hash xxhash64 md5 sha sha1 sha2 crc32 aes_encrypt ' +
    'aes_decrypt monotonically_increasing_id spark_partition_id input_file_name ' +
    'count_distinct grouping grouping_id typeof'
  ).split(' ');

  // Mount an Ace SQL editor over the plain textarea, or fall back to the textarea
  // itself if Ace is unavailable. The returned object proxies the few members
  // initEditor() uses (.value, .getAttribute, .addEventListener('input'), .focus),
  // so the rest of the editor logic is unchanged. Ctrl+Enter is bound separately.
  function createSqlEditor(textarea, host) {
    if (!window.ace || !host) {
      return textarea;
    }
    var editor = window.ace.edit(host);
    editor.setTheme('ace/theme/sqlserver');
    editor.session.setMode('ace/mode/sql');
    editor.setOptions({
      enableBasicAutocompletion: true,
      enableLiveAutocompletion: true,
      enableSnippets: false,
      fontSize: '13px',
      fontFamily: "'Roboto Mono', monospace",
      showPrintMargin: false,
      highlightActiveLine: true,
      tabSize: 2,
      useSoftTabs: true,
      newLineMode: 'unix'
    });
    editor.setOption('placeholder', host.getAttribute('data-placeholder') || '');
    editor.renderer.setScrollMargin(6, 6);

    var langTools = window.ace.require('ace/ext/language_tools');
    if (langTools) {
      var sparkCompleter = {
        getCompletions: function (ed, session, pos, prefix, callback) {
          var out = SPARK_SQL_KEYWORDS.map(function (word) {
            return { caption: word, value: word, meta: 'keyword', score: 1000 };
          });
          SPARK_SQL_FUNCTIONS.forEach(function (fn) {
            out.push({ caption: fn + '()', value: fn + '(', meta: 'function', score: 900 });
          });
          callback(null, out);
        }
      };
      // Spark keywords/functions first, then Ace's own keyword + open-buffer word
      // completers so identifiers already typed in the query are offered too.
      editor.completers = [sparkCompleter, langTools.keyWordCompleter, langTools.textCompleter];
    }

    // Inline display:none, not the `hidden` attribute — the vendored Hue CSS
    // styles `textarea` and overrides `[hidden]`, leaving the box visible.
    textarea.style.display = 'none';
    host.hidden = false;

    // The container has CSS `resize: vertical`; Ace does not track that on its
    // own, so nudge it to re-layout when the user drags the handle.
    if (window.ResizeObserver) {
      new ResizeObserver(function () {
        editor.resize();
      }).observe(host);
    }

    return {
      aceEditor: editor,
      get value() {
        return editor.getValue();
      },
      set value(text) {
        // -1 puts the cursor at the start instead of selecting the whole text.
        editor.setValue(text == null ? '' : text, -1);
      },
      getAttribute: function (name) {
        return textarea.getAttribute(name);
      },
      focus: function () {
        editor.focus();
      },
      addEventListener: function (type, handler) {
        if (type === 'input') {
          editor.on('change', function () {
            handler();
          });
        }
        // 'keydown' (Ctrl+Enter) is bound as an Ace command in initEditor.
      }
    };
  }

  // Split a SQL buffer into individual statements on top-level `;`, ignoring `;`
  // inside '...', "...", `...`, -- line comments and /* */ block comments. Each
  // statement keeps its char range. Empty/comment-only statements and a trailing
  // `;` are dropped; order is preserved. (Verified end-to-end on the stend:
  // `select 1; select 2; select 3;`, `select 'a;b'`, `-- x; y`, comment-only.)
  function splitSqlStatements(text) {
    var out = [];
    var i = 0;
    var n = text.length;
    var start = 0;
    var inS = false;
    var inD = false;
    var inB = false;
    var inLine = false;
    var inBlock = false;
    while (i < n) {
      var c = text.charAt(i);
      var c2 = text.charAt(i + 1);
      if (inLine) {
        if (c === '\n') inLine = false;
        i++;
      } else if (inBlock) {
        if (c === '*' && c2 === '/') { inBlock = false; i += 2; } else i++;
      } else if (inS) {
        if (c === "'") inS = false;
        i++;
      } else if (inD) {
        if (c === '"') inD = false;
        i++;
      } else if (inB) {
        if (c === '`') inB = false;
        i++;
      } else if (c === '-' && c2 === '-') {
        inLine = true; i += 2;
      } else if (c === '/' && c2 === '*') {
        inBlock = true; i += 2;
      } else if (c === "'") {
        inS = true; i++;
      } else if (c === '"') {
        inD = true; i++;
      } else if (c === '`') {
        inB = true; i++;
      } else if (c === ';') {
        pushStatement(out, text, start, i);
        i++;
        start = i;
      } else {
        i++;
      }
    }
    pushStatement(out, text, start, n);
    return out;
  }

  function pushStatement(out, text, start, end) {
    var raw = text.slice(start, end);
    if (!hasRunnableSql(raw)) {
      return;
    }
    var lead = raw.match(/^\s*/)[0].length;
    var trail = raw.match(/\s*$/)[0].length;
    // start/end are the trimmed range (used for cursor matching + highlight).
    out.push({ sql: raw.trim(), start: start + lead, end: end - trail });
  }

  // True if s has non-whitespace outside comments (string literals count as content).
  function hasRunnableSql(s) {
    var i = 0;
    var n = s.length;
    var inLine = false;
    var inBlock = false;
    while (i < n) {
      var c = s.charAt(i);
      var c2 = s.charAt(i + 1);
      if (inLine) {
        if (c === '\n') inLine = false;
        i++;
      } else if (inBlock) {
        if (c === '*' && c2 === '/') { inBlock = false; i += 2; } else i++;
      } else if (c === '-' && c2 === '-') {
        inLine = true; i += 2;
      } else if (c === '/' && c2 === '*') {
        inBlock = true; i += 2;
      } else if (c !== ' ' && c !== '\t' && c !== '\n' && c !== '\r') {
        return true;
      } else {
        i++;
      }
    }
    return false;
  }

  function initEditor() {
    var run = el('executeQuery');
    var runAll = el('executeAllQuery');
    var engineTabs = Array.prototype.slice.call(document.querySelectorAll('[data-sql-engine]'));
    var selectedEngine = engineTabs.length ? engineTabs[0].getAttribute('data-sql-engine') : 'kyuubi';
    var query = createSqlEditor(el('queryField'), el('queryEditor'));
    var results = el('queryResults');
    var activeMarker = null;
    var clearResultsBtn = el('clearResults');
    var activeSessionId = 'default';
    var resultsBySession = Object.create(null);
    var queryKeyPrefix = 'kudos.sql.' + query.getAttribute('data-query-owner') + '.';
    // Query results may contain sensitive data, so retain them only while this
    // browser tab lives. This survives navigation to another KUDOS tool.
    var resultKeyPrefix = 'kudos.sql.result.' + query.getAttribute('data-query-owner') + '.';
    var outputTabs = [
      { name: 'results', tab: el('resultsTab'), item: el('resultsTabItem'), pane: el('resultsPane') },
      { name: 'logs', tab: el('logsTab'), item: el('logsTabItem'), pane: el('logsPane') },
      {
        name: 'operations',
        tab: el('operationsTab'),
        item: el('operationsTabItem'),
        pane: el('operationsPane')
      }
    ];

    function showOutputTab(name) {
      outputTabs.forEach(function (output) {
        var selected = output.name === name;
        output.item.classList.toggle('active', selected);
        output.pane.classList.toggle('active', selected);
        output.pane.hidden = !selected;
        output.tab.setAttribute('aria-selected', String(selected));
        output.pane.setAttribute('aria-hidden', String(!selected));
      });
    }

    outputTabs.forEach(function (output) {
      output.tab.addEventListener('click', function (event) {
        event.preventDefault();
        showOutputTab(output.name);
      });
    });
    showOutputTab('results');

    function storedQuery(sessionId) {
      try {
        return localStorage.getItem(queryKeyPrefix + sessionId) || '';
      } catch (ignored) {
        return '';
      }
    }

    function saveQuery() {
      try {
        localStorage.setItem(queryKeyPrefix + activeSessionId, query.value);
      } catch (ignored) {
        // The editor still works when storage is disabled by browser policy.
      }
    }

    function forgetQuery(sessionId) {
      try {
        localStorage.removeItem(queryKeyPrefix + sessionId);
      } catch (ignored) {
        // Nothing to remove when storage is unavailable.
      }
    }

    function storedResult(sessionId) {
      try {
        var value = sessionStorage.getItem(resultKeyPrefix + sessionId);
        return value ? JSON.parse(value) : null;
      } catch (ignored) {
        return null;
      }
    }

    function rememberResult(sessionId, result) {
      resultsBySession[sessionId] = result;
      try {
        sessionStorage.setItem(resultKeyPrefix + sessionId, JSON.stringify(result));
      } catch (ignored) {
        // Rendering continues when storage is disabled or full.
      }
    }

    function forgetResult(sessionId) {
      delete resultsBySession[sessionId];
      try {
        sessionStorage.removeItem(resultKeyPrefix + sessionId);
      } catch (ignored) {
        // Nothing to remove when storage is unavailable.
      }
    }

    function renderEmptyResult() {
      results.innerHTML = '<div class="k8s-muted">Run a query to see its results.</div>';
    }

    function renderResultInto(gridEl, result) {
      gridEl.innerHTML = '';
      if (result && result.message) {
        gridEl.appendChild(element('div', { class: 'k8s-muted', text: result.message }));
        return;
      }
      if (!result || !result.rows || !result.rows.length) {
        gridEl.appendChild(element('div', { class: 'k8s-muted', text: 'The query returned no rows.' }));
        return;
      }
      var scroll = element('div', { class: 'k8s-result-scroll' });
      scroll.appendChild(buildTable(result.columns, result.rows));
      gridEl.appendChild(scroll);
    }

    function renderResult(result) {
      results.innerHTML = '';
      var grid = element('div');
      results.appendChild(grid);
      renderResultInto(grid, result);
    }

    function restoreResult() {
      var result = resultsBySession[activeSessionId] || storedResult(activeSessionId);
      if (result) {
        resultsBySession[activeSessionId] = result;
        renderResult(result);
      } else {
        renderEmptyResult();
      }
      if (selectedEngine === 'trino') {
        renderTrinoLogs(result);
      }
    }

    function clearResults() {
      forgetResult(activeSessionId);
      renderEmptyResult();
    }

    function useSession(sessionId) {
      if (activeSessionId === sessionId) {
        return;
      }
      saveQuery();
      activeSessionId = sessionId;
      query.value = storedQuery(sessionId);
      restoreResult();
    }

    query.value = storedQuery(activeSessionId);
    restoreResult();

    function cursorOffset() {
      if (query.aceEditor) {
        return query.aceEditor.session.doc.positionToIndex(query.aceEditor.getCursorPosition());
      }
      return typeof query.selectionStart === 'number' ? query.selectionStart : query.value.length;
    }

    function selectedText() {
      if (query.aceEditor) {
        return query.aceEditor.getSelectedText();
      }
      if (typeof query.selectionStart === 'number') {
        return query.value.slice(query.selectionStart, query.selectionEnd);
      }
      return '';
    }

    // The statements Execute/Ctrl+Enter should run: the selection if any, else
    // the statement under the cursor (selection > active statement). Execute all
    // runs every statement in the buffer.
    function statementsToRun(runAllStatements) {
      if (runAllStatements) {
        return splitSqlStatements(query.value);
      }
      var selection = selectedText();
      if (selection && selection.trim()) {
        return splitSqlStatements(selection);
      }
      var all = splitSqlStatements(query.value);
      if (!all.length) {
        return [];
      }
      var offset = cursorOffset();
      for (var k = 0; k < all.length; k++) {
        if (offset >= all[k].start && offset <= all[k].end) {
          return [all[k]];
        }
      }
      return [all[all.length - 1]];
    }

    function buildStatementStrip(reports, gridEl) {
      var strip = element('div', { class: 'k8s-stmt-strip' });
      reports.forEach(function (report, index) {
        var chip = element('button', {
          type: 'button',
          class: 'k8s-stmt-chip' + (report.ok ? '' : ' k8s-stmt-error'),
          text: 'Q' + (index + 1) + (report.ok ? '' : ' ✕'),
          title: report.sql
        });
        chip.addEventListener('click', function () {
          var chips = strip.querySelectorAll('.k8s-stmt-chip');
          for (var c = 0; c < chips.length; c++) {
            chips[c].classList.remove('active');
          }
          chip.classList.add('active');
          if (report.ok) {
            renderResultInto(gridEl, report.result);
          } else {
            showError(gridEl, report.error);
          }
        });
        strip.appendChild(chip);
      });
      return strip;
    }

    function renderReports(sessionId, reports) {
      // Remember the last statement that produced a grid (fall back to any ok
      // result) so Export and tab restore work.
      var remembered = null;
      for (var k = reports.length - 1; k >= 0 && !remembered; k--) {
        if (reports[k].ok && reports[k].result && reports[k].result.rows && reports[k].result.rows.length) {
          remembered = reports[k].result;
        }
      }
      if (!remembered) {
        for (var m = reports.length - 1; m >= 0 && !remembered; m--) {
          if (reports[m].ok && reports[m].result) {
            remembered = reports[m].result;
          }
        }
      }
      if (remembered) {
        rememberResult(sessionId, remembered);
      } else {
        forgetResult(sessionId);
      }
      if (activeSessionId !== sessionId) {
        return;
      }
      results.innerHTML = '';
      var grid = element('div');
      if (reports.length > 1) {
        var strip = buildStatementStrip(reports, grid);
        results.appendChild(strip);
        var lastChip = strip.querySelectorAll('.k8s-stmt-chip')[reports.length - 1];
        if (lastChip) {
          lastChip.classList.add('active');
        }
      }
      results.appendChild(grid);
      var last = reports[reports.length - 1];
      if (selectedEngine === 'trino') {
        renderTrinoLogs(last.ok ? last.result : null);
      }
      if (last.ok) {
        renderResultInto(grid, last.result);
      } else {
        showError(grid, last.error);
      }
    }

    // Run statements sequentially, awaiting each; stop at the first error.
    function runStatements(statements) {
      if (!statements.length) {
        return Promise.resolve(null);
      }
      var executionSessionId = activeSessionId;
      var executionEngine = selectedEngine;
      forgetResult(executionSessionId);
      showOutputTab('results');
      run.disabled = true;
      if (runAll) runAll.disabled = true;
      var reports = [];
      var index = 0;
      function step() {
        if (index >= statements.length) {
          return Promise.resolve();
        }
        results.innerHTML =
          '<div class="k8s-muted">Executing ' + (index + 1) + ' / ' + statements.length + '…</div>';
        return request(UI_API + '/sql/execute', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ sql: statements[index].sql, engine: executionEngine })
        }).then(
          function (result) {
            reports.push({ sql: statements[index].sql, ok: true, result: result });
            index++;
            return step();
          },
          function (error) {
            reports.push({ sql: statements[index].sql, ok: false, error: error });
            return Promise.reject(reports);
          }
        );
      }
      return step()
        .then(function () { return reports; }, function () { return reports; })
        .then(function () {
          renderReports(executionSessionId, reports);
          if (executionEngine === 'trino') {
            loadTrinoHistory();
          }
          run.disabled = false;
          if (runAll) runAll.disabled = false;
          return resultsBySession[executionSessionId] || null;
        });
    }

    function execute() {
      return runStatements(statementsToRun(false));
    }

    var exportBtn = el('exportExcel');

    function exportExcel() {
      exportBtn.disabled = true;
      var displayedResult = resultsBySession[activeSessionId];
      (displayedResult ? Promise.resolve(displayedResult) : execute())
        .then(function (result) {
          if (!result) {
            return null;
          }
          return browserFetch(UI_API + '/sql/export/results', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(result)
          });
        })
        .then(function (response) {
          if (!response) {
            return null;
          }
          if (!response.ok) {
            return response.text().then(function (body) {
              throw new Error(body || 'HTTP ' + response.status);
            });
          }
          return response.blob();
        })
        .then(function (blob) {
          if (!blob) {
            return;
          }
          var url = URL.createObjectURL(blob);
          var link = document.createElement('a');
          link.href = url;
          link.download = 'query-results.xlsx';
          document.body.appendChild(link);
          link.click();
          document.body.removeChild(link);
          URL.revokeObjectURL(url);
        })
        .catch(function (error) {
          showError(results, error);
        })
        .then(function () {
          exportBtn.disabled = false;
        });
    }

    // Mark the statement Execute/Ctrl+Enter would run (skipped while selecting,
    // since the selection is its own cue).
    function highlightActiveStatement() {
      var editor = query.aceEditor;
      if (!editor) {
        return;
      }
      var session = editor.session;
      if (activeMarker != null) {
        session.removeMarker(activeMarker);
        activeMarker = null;
      }
      if (editor.getSelectedText()) {
        return;
      }
      var all = splitSqlStatements(editor.getValue());
      if (!all.length) {
        return;
      }
      var offset = session.doc.positionToIndex(editor.getCursorPosition());
      var stmt = all[all.length - 1];
      for (var k = 0; k < all.length; k++) {
        if (offset >= all[k].start && offset <= all[k].end) {
          stmt = all[k];
          break;
        }
      }
      var Range = window.ace.require('ace/range').Range;
      var s = session.doc.indexToPosition(stmt.start);
      var e = session.doc.indexToPosition(stmt.end);
      activeMarker = session.addMarker(
        new Range(s.row, s.column, e.row, e.column), 'k8s-active-stmt', 'text', false);
    }

    run.addEventListener('click', execute);
    if (runAll) {
      runAll.addEventListener('click', function () {
        runStatements(statementsToRun(true));
      });
    }
    exportBtn.addEventListener('click', exportExcel);
    clearResultsBtn.addEventListener('click', clearResults);
    query.addEventListener('input', saveQuery);
    if (query.aceEditor) {
      query.aceEditor.selection.on('changeCursor', highlightActiveStatement);
      query.aceEditor.selection.on('changeSelection', highlightActiveStatement);
      query.aceEditor.session.on('change', highlightActiveStatement);
      highlightActiveStatement();
      query.aceEditor.commands.addCommand({
        name: 'kudosExecute',
        bindKey: { win: 'Ctrl-Enter', mac: 'Command-Enter' },
        exec: function () {
          execute();
        }
      });
    } else {
      query.addEventListener('keydown', function (event) {
        if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
          execute();
        }
      });
    }

    /* -------------------------------------------------------- sessions */

    var sessionsPanel = el('sessionsPanel');
    var sessionBar = el('sessionBar');
    var newSessionBtn = el('newSession');
    var closeAllBtn = el('closeAllSessions');
    var noSessions = el('noSessions');
    var sessionMonitorSummary = el('sessionMonitorSummary');
    var sessionMonitorWarning = el('sessionMonitorWarning');
    var sessionMonitorMetrics = el('sessionMonitorMetrics');
    var sessionMonitorOperations = el('sessionMonitorOperations');
    var sessionMonitorLogs = el('sessionMonitorLogs');
    var knownSessions = [];

    function engineSupportsSessions() {
      var activeTab = engineTabs.filter(function (tab) {
        return tab.getAttribute('data-sql-engine') === selectedEngine;
      })[0];
      if (!activeTab) {
        return true;
      }
      return activeTab.getAttribute('data-supports-sessions') === 'true';
    }

    function selectEngine(engine) {
      selectedEngine = engine || selectedEngine;
      engineTabs.forEach(function (tab) {
        var active = tab.getAttribute('data-sql-engine') === selectedEngine;
        tab.parentElement.classList.toggle('active', active);
        tab.setAttribute('aria-selected', String(active));
      });
      var supportsSessions = engineSupportsSessions();
      var trino = selectedEngine === 'trino';
      sessionBar.classList.toggle('k8s-hidden', !supportsSessions);
      sessionMonitor.classList.toggle('k8s-hidden', !supportsSessions);
      noSessions.classList.toggle('k8s-hidden', !supportsSessions);
      el('logsTabItem').classList.toggle('k8s-hidden', !supportsSessions);
      el('operationsTabItem').classList.toggle('k8s-hidden', !supportsSessions && !trino);
      el('operationsTab').textContent = trino ? 'History' : 'Session History';
      if (!supportsSessions) {
        showOutputTab('results');
        useSession('engine-' + selectedEngine);
        if (trino) {
          loadTrinoHistory();
        }
        run.disabled = false;
        return;
      }
      loadSessions();
    }

    function loadSessions() {
      if (!engineSupportsSessions()) {
        return;
      }
      request(UI_API + '/sessions')
        .then(renderSessions)
        .catch(function (error) { showError(sessionsPanel, error); });
    }

    function metric(label, value) {
      var shown = value === null || value === undefined || value === '' ? '—' : value;
      return element('div', { class: 'k8s-session-metric' }, [
        element('strong', { text: label }),
        ': ' + shown
      ]);
    }

    function loadMonitor(session) {
      if (!session) {
        sessionMonitorSummary.innerHTML = '';
        sessionMonitorSummary.appendChild(element('strong', { text: 'No active Kyuubi session.' }));
        sessionMonitorWarning.classList.add('k8s-hidden');
        sessionMonitorMetrics.innerHTML = '';
        sessionMonitorOperations.innerHTML = '';
        sessionMonitorOperations.appendChild(text('No operations yet.'));
        sessionMonitorLogs.textContent = 'No operation logs yet.';
        return;
      }
      request(UI_API + '/sessions/' + encodeURIComponent(session.id) + '/monitor')
        .then(function (monitor) {
          if (activeSessionId !== session.id) {
            return;
          }
          sessionMonitorSummary.innerHTML = '';
          sessionMonitorSummary.appendChild(
            element('strong', { text: 'Session ' + monitor.state + ': ' + monitor.message })
          );
          sessionMonitorWarning.classList.toggle('k8s-hidden', !monitor.monitoringError);
          sessionMonitorWarning.textContent = monitor.monitoringError
            ? 'Monitoring update failed; showing the last known values. ' + monitor.monitoringError
            : '';
          sessionMonitorMetrics.innerHTML = '';
          [
            metric('Session ID', monitor.kyuubiSessionId),
            metric('Engine', monitor.engineName || monitor.engineId),
            metric('Operations', monitor.totalOperations),
            metric('Running', monitor.runningOperations),
            metric('Kyuubi pool', monitor.executorPoolActiveCount + '/' + monitor.executorPoolSize),
            metric('Queue', monitor.executorPoolQueueSize)
          ].forEach(function (item) { sessionMonitorMetrics.appendChild(item); });
          renderOperations(session, monitor.operations || []);
          sessionMonitorLogs.textContent = (monitor.logs || []).join('\n') || 'No operation logs yet.';
        })
        .catch(function (error) {
          sessionMonitorWarning.classList.remove('k8s-hidden');
          sessionMonitorWarning.textContent = error.message || String(error);
        });
    }

    function renderOperations(session, operations) {
      var previous = sessionMonitorOperations.querySelector('.k8s-operations-scroll');
      var scrollTop = previous ? previous.scrollTop : 0;
      sessionMonitorOperations.innerHTML = '';
      if (!operations.length) {
        sessionMonitorOperations.appendChild(text('No operations yet.'));
        return;
      }
      var scroll = element('div', { class: 'k8s-operations-scroll' });
      var table = element('table', { class: 'table table-condensed table-huedatatable' });
      var head = document.createElement('thead');
      var header = document.createElement('tr');
      ['State', 'Statement', 'Runs', 'Started', 'Error', 'Actions'].forEach(function (label) {
        header.appendChild(element('th', { text: label }));
      });
      head.appendChild(header);
      table.appendChild(head);
      var body = document.createElement('tbody');
      operations.forEach(function (operation) {
        var row = document.createElement('tr');
        [
          operation.state,
          operation.statement,
          String(operation.executionCount || 1),
          formatDate(operation.startedAtEpochMs),
          operation.error || ''
        ].forEach(function (value) {
          row.appendChild(element('td', { text: value }));
        });
        var actions = element('span', { class: 'k8s-row-actions' });
        var rerun = button('Run', 'k8s-link', function () {
          rerunOperation(session, operation);
        });
        rerun.title = 'Run this SQL in the active session';
        var open = button('Open', 'k8s-link', function () {
          query.value = operation.statement;
          saveQuery();
          toast('SQL opened in Query Editor');
        });
        open.title = 'Open SQL in Query Editor without running it';
        var download = element('a', {
          class: 'k8s-link',
          href: UI_API + '/sessions/' + encodeURIComponent(session.id)
            + '/operations/' + encodeURIComponent(operation.id) + '/sql',
          text: 'Download',
          title: 'Download SQL file'
        });
        actions.appendChild(rerun);
        actions.appendChild(open);
        actions.appendChild(download);
        var actionCell = document.createElement('td');
        actionCell.appendChild(actions);
        row.appendChild(actionCell);
        body.appendChild(row);
      });
      table.appendChild(body);
      scroll.appendChild(table);
      sessionMonitorOperations.appendChild(scroll);
      scroll.scrollTop = scrollTop;
    }

    function renderTrinoLogs(result) {
      var logs = result && result.logs ? result.logs : [];
      el('logsTabItem').classList.toggle('k8s-hidden', logs.length === 0);
      sessionMonitorLogs.textContent = logs.join('\n');
    }

    function loadTrinoHistory() {
      request(UI_API + '/trino/history')
        .then(renderTrinoHistory)
        .catch(function (error) { showError(sessionMonitorOperations, error); });
    }

    function renderTrinoHistory(entries) {
      if (selectedEngine !== 'trino') {
        return;
      }
      var previous = sessionMonitorOperations.querySelector('.k8s-operations-scroll');
      var scrollTop = previous ? previous.scrollTop : 0;
      sessionMonitorOperations.innerHTML = '';
      if (!entries.length) {
        sessionMonitorOperations.appendChild(text('No Trino queries yet.'));
        return;
      }
      var scroll = element('div', { class: 'k8s-operations-scroll' });
      var table = element('table', { class: 'table table-condensed table-huedatatable' });
      var head = document.createElement('thead');
      var header = document.createElement('tr');
      ['State', 'Statement', 'Started', 'Error', 'Actions'].forEach(function (label) {
        header.appendChild(element('th', { text: label }));
      });
      head.appendChild(header);
      table.appendChild(head);
      var body = document.createElement('tbody');
      entries.forEach(function (entry) {
        var row = document.createElement('tr');
        [entry.state, entry.statement, formatDate(entry.startedAtEpochMs), entry.error || '']
          .forEach(function (value) { row.appendChild(element('td', { text: value })); });
        var actions = element('span', { class: 'k8s-row-actions' });
        var rerun = button('Run', 'k8s-link', function () {
          query.value = entry.statement;
          saveQuery();
          runStatements([{ sql: entry.statement }]);
        });
        rerun.title = 'Run this SQL in Trino';
        var open = button('Open', 'k8s-link', function () {
          query.value = entry.statement;
          saveQuery();
          toast('SQL opened in Query Editor');
        });
        open.title = 'Open SQL in Query Editor without running it';
        var download = element('a', {
          class: 'k8s-link',
          href: UI_API + '/trino/history/' + encodeURIComponent(entry.id) + '/sql',
          text: 'Download',
          title: 'Download SQL file'
        });
        actions.appendChild(rerun);
        actions.appendChild(open);
        actions.appendChild(download);
        var actionCell = document.createElement('td');
        actionCell.appendChild(actions);
        row.appendChild(actionCell);
        body.appendChild(row);
      });
      table.appendChild(body);
      scroll.appendChild(table);
      sessionMonitorOperations.appendChild(scroll);
      scroll.scrollTop = scrollTop;
    }

    function rerunOperation(session, operation) {
      var executionSessionId = session.id;
      run.disabled = true;
      showOutputTab('results');
      results.innerHTML = '<div class="k8s-muted">Executing saved SQL…</div>';
      request(
        UI_API + '/sessions/' + encodeURIComponent(session.id) + '/operations/'
          + encodeURIComponent(operation.id) + '/execute',
        { method: 'POST' }
      )
        .then(function (result) {
          rememberResult(executionSessionId, result);
          if (activeSessionId === executionSessionId) {
            renderResult(result);
          }
          loadSessions();
        })
        .catch(function (error) {
          if (activeSessionId === executionSessionId) {
            showError(results, error);
          }
        })
        .then(function () { run.disabled = false; });
    }

    function activateSession(session) {
      if (session.active) {
        return;
      }
      send(UI_API + '/sessions/activate', { id: session.id })
        .then(loadSessions)
        .catch(function (error) { toast(error.message || String(error)); });
    }

    function closeSession(session) {
      send(UI_API + '/sessions/stop', { id: session.id })
        .then(function () {
          forgetQuery(session.id);
          forgetResult(session.id);
          loadSessions();
        })
        .catch(function (error) { toast(error.message || String(error)); });
    }

    function renderSessions(sessions) {
      knownSessions = sessions;
      sessionsPanel.innerHTML = '';
      noSessions.classList.toggle('k8s-hidden', sessions.length > 0);
      closeAllBtn.disabled = sessions.length === 0;
      var active = sessions.filter(function (session) { return session.active; })[0];
      useSession(active ? active.id : 'default');
      run.disabled = Boolean(active && active.state !== 'READY');
      loadMonitor(active);
      if (!sessions.length) {
        return;
      }
      sessions.forEach(function (session) {
        var params = session.sparkParams
          ? session.sparkParams.replace(/\r?\n/g, ', ')
          : 'cluster defaults';
        var label = button(session.name, 'k8s-session-tab-label', function () {
          activateSession(session);
        });
        label.setAttribute('role', 'tab');
        label.setAttribute('aria-selected', String(session.active));
        var restart = button('⚙', 'k8s-session-tab-action', function () {
          restartSession(session);
        });
        restart.title = 'Restart session';
        restart.setAttribute('aria-label', 'Restart session ' + session.name);
        var close = button('×', 'k8s-session-tab-action k8s-session-tab-close', function () {
          closeSession(session);
        });
        close.title = 'Close session';
        close.setAttribute('aria-label', 'Close session ' + session.name);
        var status = session.state === 'READY' ? 'ready'
          : session.state === 'FAILED' ? 'failed' : 'starting';
        var indicator = element('span', {
          class: 'k8s-session-indicator k8s-session-' + status,
          title: session.message,
          'aria-label': 'Session ' + session.state
        });
        var tab = element(
          'div',
          {
            class: 'k8s-session-tab' + (session.active ? ' k8s-session-active' : ''),
            title: params
          },
          [
            label,
            indicator,
            restart,
            close
          ]
        );
        sessionsPanel.appendChild(tab);
      });
    }

    function sessionForm(session, title, url) {
      var name = textInput(session ? session.name : '', 'session name');
      if (session) {
        name.disabled = true;
      }
      var params = element('textarea', {
        class: 'k8s-input k8s-textarea',
        placeholder: 'One per line, e.g.\nspark.executor.memory=2g\nspark.executor.cores=2'
      });
      params.value = session ? session.sparkParams || '' : '';
      var body = element('div', {}, [field('Name', name), field('Spark parameters', params)]);
      openModal(title, body, [
        { label: 'Cancel', action: function (h) { h.close(); } },
        {
          label: 'Start',
          class: 'btn btn-small btn-primary',
          action: function (h) {
            h.close();
            toast('Starting session (launching engine)…');
            var payload = session
              ? { id: session.id, sparkParams: params.value }
              : { name: name.value, sparkParams: params.value };
            send(url, payload)
              .then(function () { toast('Session starting'); loadSessions(); })
              .catch(function (error) { toast(error.message || String(error)); loadSessions(); });
          }
        }
      ]);
    }

    function restartSession(session) {
      sessionForm(session, 'Restart session', UI_API + '/sessions/restart');
    }

    if (newSessionBtn) {
      newSessionBtn.addEventListener('click', function () {
        sessionForm(null, 'New session', UI_API + '/sessions/start');
      });
    }

    closeAllBtn.addEventListener('click', function () {
      if (!knownSessions.length) {
        return;
      }
      confirmModal('Close all Kyuubi sessions?', function () {
        Promise.all(
          knownSessions.map(function (session) {
            return send(UI_API + '/sessions/stop', { id: session.id });
          })
        )
          .then(function () {
            knownSessions.forEach(function (session) { forgetQuery(session.id); });
            knownSessions.forEach(function (session) { forgetResult(session.id); });
            loadSessions();
          })
          .catch(function (error) { toast(error.message || String(error)); });
      });
    });

    engineTabs.forEach(function (tab) {
      tab.addEventListener('click', function (event) {
        event.preventDefault();
        selectEngine(tab.getAttribute('data-sql-engine'));
      });
    });
    selectEngine();
    window.setInterval(loadSessions, 1000);
  }

  /* ------------------------------------------------------- storage browsers */

  function initBrowser(kind) {
    var listing = el('listing');
    var breadcrumbs = el('breadcrumbs');
    var preview = el('preview');
    var refresh = el('refreshBrowser');
    var newFolderBtn = el('newFolder');
    var uploadInput = el('uploadFile');
    var base = UI_API + '/' + kind;
    var currentPath = '/';

    function joinPath(dir, name) {
      return (dir.charAt(dir.length - 1) === '/' ? dir : dir + '/') + name;
    }

    function rowActions(entry) {
      var actions = element('span', { class: 'k8s-row-actions' }, []);
      if (!entry.directory) {
        actions.appendChild(
          element(
            'a',
            {
              class: 'k8s-link',
              href: base + '/download?path=' + encodeURIComponent(entry.path),
              title: 'Download'
            },
            [text('download')]
          )
        );
      }
      actions.appendChild(button('rename', 'k8s-link', function () { renameEntry(entry); }));
      actions.appendChild(button('chmod', 'k8s-link', function () { chmodEntry(entry); }));
      actions.appendChild(button('chown', 'k8s-link', function () { chownEntry(entry); }));
      actions.appendChild(
        button('delete', 'k8s-link k8s-link-danger', function () { deleteEntry(entry); })
      );
      return actions;
    }

    function renameEntry(entry) {
      var dest = textInput(entry.path, 'new full path');
      openModal('Rename / move', field('Destination path', dest), [
        { label: 'Cancel', action: function (h) { h.close(); } },
        {
          label: 'Save',
          class: 'btn btn-small btn-primary',
          action: function (h) {
            send(base + '/rename', { path: entry.path, destination: dest.value.trim() })
              .then(function () { h.close(); toast('Renamed'); navigate(currentPath); })
              .catch(function (e) { toast(e.message || String(e)); });
          }
        }
      ]);
    }

    function chmodEntry(entry) {
      var perm = textInput(entry.permission, 'e.g. 755');
      openModal('Change permissions', field('Permission (octal)', perm), [
        { label: 'Cancel', action: function (h) { h.close(); } },
        {
          label: 'Save',
          class: 'btn btn-small btn-primary',
          action: function (h) {
            send(base + '/chmod', { path: entry.path, permission: perm.value.trim() })
              .then(function () { h.close(); toast('Permissions changed'); navigate(currentPath); })
              .catch(function (e) { toast(e.message || String(e)); });
          }
        }
      ]);
    }

    function chownEntry(entry) {
      var owner = textInput(entry.owner, 'owner');
      var group = textInput(entry.group, 'group');
      openModal('Change owner', element('div', {}, [field('Owner', owner), field('Group', group)]), [
        { label: 'Cancel', action: function (h) { h.close(); } },
        {
          label: 'Save',
          class: 'btn btn-small btn-primary',
          action: function (h) {
            send(base + '/chown', {
              path: entry.path,
              owner: owner.value.trim(),
              group: group.value.trim()
            })
              .then(function () { h.close(); toast('Owner changed'); navigate(currentPath); })
              .catch(function (e) { toast(e.message || String(e)); });
          }
        }
      ]);
    }

    function deleteEntry(entry) {
      confirmDestructive(
        'Delete ' + entry.name + (entry.directory ? '? (recursive)' : '?'),
        'delete',
        function () {
          send(base + '/delete', { path: entry.path, recursive: entry.directory })
            .then(function () { toast('Deleted'); navigate(currentPath); })
            .catch(function (e) { toast(e.message || String(e)); });
        }
      );
    }

    if (newFolderBtn) {
      newFolderBtn.addEventListener('click', function () {
        var name = textInput('', 'folder name');
        openModal('New folder', field('Name', name), [
          { label: 'Cancel', action: function (h) { h.close(); } },
          {
            label: 'Create',
            class: 'btn btn-small btn-primary',
            action: function (h) {
              if (!name.value.trim()) {
                toast('A name is required');
                return;
              }
              send(base + '/mkdir', { path: joinPath(currentPath, name.value.trim()) })
                .then(function () { h.close(); toast('Folder created'); navigate(currentPath); })
                .catch(function (e) { toast(e.message || String(e)); });
            }
          }
        ]);
      });
    }
    if (uploadInput) {
      uploadInput.addEventListener('change', function () {
        if (!uploadInput.files || !uploadInput.files.length) {
          return;
        }
        var form = new FormData();
        form.append('file', uploadInput.files[0]);
        request(base + '/upload?path=' + encodeURIComponent(currentPath), {
          method: 'POST',
          body: form
        })
          .then(function () { toast('Uploaded'); uploadInput.value = ''; navigate(currentPath); })
          .catch(function (e) { toast(e.message || String(e)); uploadInput.value = ''; });
      });
    }

    function navigate(path) {
      currentPath = path;
      history.replaceState(null, '', '?path=' + encodeURIComponent(path));
      renderBreadcrumbs(path);
      preview.innerHTML = '';
      listing.innerHTML = '<div class="k8s-muted">Loading…</div>';
      request(base + '/list?path=' + encodeURIComponent(path))
        .then(function (entries) {
          listing.innerHTML = '';
          if (!entries.length) {
            var empty = document.createElement('div');
            empty.className = 'k8s-muted';
            empty.appendChild(text('This directory is empty.'));
            listing.appendChild(empty);
            return;
          }
          listing.appendChild(
            buildTable(
              ['', 'Name', 'Size', 'User', 'Group', 'Permissions', 'Date', 'Actions'],
              entries,
              function (entry) {
                var icon = document.createElement('i');
                icon.className =
                  'fa fa-fw muted ' + (entry.directory ? 'fa-folder-o' : 'fa-file-o');
                var link = document.createElement('a');
                link.appendChild(text(entry.name));
                link.href = 'javascript:void(0)';
                link.addEventListener('click', function () {
                  if (entry.directory) {
                    navigate(entry.path);
                  } else {
                    showPreview(entry.path);
                  }
                });
                return [
                  icon,
                  link,
                  entry.directory ? '' : formatSize(entry.size),
                  entry.owner,
                  entry.group,
                  entry.permission,
                  formatDate(entry.modificationTime),
                  rowActions(entry)
                ];
              }
            )
          );
        })
        .catch(function (error) {
          showError(listing, error);
        });
    }

    function showPreview(path) {
      preview.innerHTML = '<div class="k8s-muted">Loading preview…</div>';
      request(base + '/preview?path=' + encodeURIComponent(path))
        .then(function (body) {
          preview.innerHTML = '';
          var heading = document.createElement('h4');
          heading.appendChild(text(path));
          var pre = document.createElement('pre');
          pre.className = 'k8s-preview';
          pre.appendChild(text(body));
          preview.appendChild(heading);
          preview.appendChild(pre);
        })
        .catch(function (error) {
          showError(preview, error);
        });
    }

    function renderBreadcrumbs(path) {
      breadcrumbs.innerHTML = '';
      var segments = path.split('/').filter(function (segment) {
        return segment.length > 0;
      });
      var accumulated = '';
      appendCrumb('/', '/');
      segments.forEach(function (segment, index) {
        accumulated += '/' + segment;
        // The root crumb is already a slash; a separator before the first
        // segment would render the path as "/ / user".
        if (index > 0) {
          breadcrumbs.appendChild(text(' / '));
        }
        appendCrumb(segment, accumulated);
      });

      function appendCrumb(label, target) {
        var link = document.createElement('a');
        link.appendChild(text(label));
        link.href = 'javascript:void(0)';
        link.addEventListener('click', function () {
          navigate(target);
        });
        breadcrumbs.appendChild(link);
      }
    }

    if (refresh) {
      refresh.addEventListener('click', function () {
        navigate(currentPath);
      });
    }

    navigate(listing.getAttribute('data-path') || '/');
  }

  /* ------------------------------------------------------------------- hbase */

  var HBASE_COMPRESSION = ['NONE', 'GZ', 'SNAPPY', 'LZ4', 'ZSTD'];
  var HBASE_BLOOM = ['NONE', 'ROW', 'ROWCOL'];
  var HBASE_ENCODING = ['NONE', 'PREFIX', 'DIFF', 'FAST_DIFF', 'ROW_INDEX_V1'];
  var MAX_HBASE_COLUMNS = 60;

  function toast(message) {
    var note = element('div', { class: 'k8s-toast', text: message });
    document.body.appendChild(note);
    setTimeout(function () {
      if (note.parentNode) {
        note.parentNode.removeChild(note);
      }
    }, 2600);
  }

  function selectInput(options, value) {
    var control = element('select', { class: 'k8s-input' });
    options.forEach(function (option) {
      var opt = element('option', { value: option, text: option });
      if (option === value) {
        opt.selected = true;
      }
      control.appendChild(opt);
    });
    return control;
  }

  function textInput(value, placeholder) {
    return element('input', {
      type: 'text',
      class: 'k8s-input',
      value: value == null ? '' : value,
      placeholder: placeholder || ''
    });
  }

  function numberInput(value) {
    return element('input', { type: 'number', class: 'k8s-input k8s-input-narrow', value: value });
  }

  function checkbox(checked) {
    var input = element('input', { type: 'checkbox' });
    input.checked = !!checked;
    return input;
  }

  // Build the editor for one column family; read() returns its JSON form.
  function familyForm(existing) {
    var name = textInput(existing ? existing.name : '', 'name');
    if (existing) {
      name.disabled = true;
    }
    var maxVersions = numberInput(existing ? existing.maxVersions : 1);
    var minVersions = numberInput(existing ? existing.minVersions : 0);
    var compression = selectInput(HBASE_COMPRESSION, existing ? existing.compression : 'NONE');
    var ttl = numberInput(existing ? existing.timeToLive : 2147483647);
    var blockCache = checkbox(existing ? existing.blockCacheEnabled : true);
    var bloom = selectInput(HBASE_BLOOM, existing ? existing.bloomFilterType : 'ROW');
    var encoding = selectInput(HBASE_ENCODING, existing ? existing.dataBlockEncoding : 'NONE');
    var inMemory = checkbox(existing ? existing.inMemory : false);

    var node = element('div', { class: 'k8s-family-form' }, [
      field('Family', name),
      element('div', { class: 'k8s-field-row' }, [
        field('Max versions', maxVersions),
        field('Min versions', minVersions),
        field('TTL (s)', ttl)
      ]),
      element('div', { class: 'k8s-field-row' }, [
        field('Compression', compression),
        field('Bloom filter', bloom),
        field('Encoding', encoding)
      ]),
      element('div', { class: 'k8s-field-row' }, [
        field('Block cache', blockCache),
        field('In memory', inMemory)
      ])
    ]);

    return {
      node: node,
      read: function () {
        return {
          name: name.value.trim(),
          maxVersions: parseInt(maxVersions.value, 10),
          minVersions: parseInt(minVersions.value, 10),
          compression: compression.value,
          timeToLive: parseInt(ttl.value, 10),
          blockCacheEnabled: blockCache.checked,
          bloomFilterType: bloom.value,
          dataBlockEncoding: encoding.value,
          inMemory: inMemory.checked
        };
      }
    };
  }

  function initHbase() {
    var tablesEl = el('tableList');
    var viewEl = el('tableView');
    var current = null;

    el('newTableButton').addEventListener('click', openCreateTable);
    el('refreshTables').addEventListener('click', function () {
      loadTables(current ? current.name : null);
    });

    // A filter box above the list keeps it usable with thousands of tables; the
    // list itself scrolls (see .k8s-table-list in the stylesheet).
    var tableFilter = textInput('', 'filter tables…');
    tableFilter.className = 'k8s-input k8s-table-filter';
    tablesEl.parentNode.insertBefore(tableFilter, tablesEl);
    tableFilter.addEventListener('input', applyTableFilter);

    function applyTableFilter() {
      var query = tableFilter.value.trim().toLowerCase();
      Array.prototype.forEach.call(tablesEl.querySelectorAll('li'), function (li) {
        var name = li.textContent.toLowerCase();
        li.style.display = !query || name.indexOf(query) !== -1 ? '' : 'none';
      });
    }

    /* --------------------------------------------------------- table list */

    function loadTables(selectName) {
      request(UI_API + '/hbase/tables')
        .then(function (tables) {
          tablesEl.innerHTML = '';
          if (!tables.length) {
            tablesEl.appendChild(element('li', { class: 'k8s-muted', text: 'No tables.' }));
            return;
          }
          tables.forEach(function (table) {
            var link = element('a', { href: 'javascript:void(0)' }, [
              element('span', { text: table.name })
            ]);
            var li = element('li', {}, [link]);
            link.addEventListener('click', function () {
              Array.prototype.forEach.call(tablesEl.querySelectorAll('li'), function (other) {
                other.className = '';
              });
              li.className = 'active';
              openTable(table);
            });
            tablesEl.appendChild(li);
            if (table.name === selectName) {
              li.className = 'active';
              openTable(table);
            }
          });
          applyTableFilter();
        })
        .catch(function (error) {
          showError(tablesEl, error);
        });
    }

    /* ------------------------------------------------------------ actions */

    function tableActions(table) {
      return element('div', { class: 'k8s-table-actions' }, [
        button('New row', 'btn btn-small btn-primary', function () {
          openRowEditor(table.name, null);
        }),
        button('Bulk upload', 'btn btn-small', function () {
          openBulkUpload(table.name);
        }),
        button('Families', 'btn btn-small', function () {
          openFamilies(table.name);
        }),
        button('Regions', 'btn btn-small', function () {
          openRegions(table.name);
        }),
        button('Drop', 'btn btn-small btn-danger', function () {
          confirmDestructive('Drop table ' + table.name + '? This cannot be undone.', 'drop', function () {
            send(UI_API + '/hbase/table/delete', { table: table.name })
              .then(function () {
                toast('Table dropped');
                current = null;
                viewEl.innerHTML = '';
                viewEl.appendChild(
                  element('div', { class: 'k8s-muted', text: 'Pick a table to browse its rows.' })
                );
                loadTables(null);
              })
              .catch(function (error) {
                toast(error.message || String(error));
              });
          });
        })
      ]);
    }

    /* ------------------------------------------------------- table browser */

    function openTable(table) {
      current = table;
      var search = textInput('', 'row key, or prefix with *');
      var filter = textInput('', "filter e.g. SingleColumnValueFilter('cf','q',=,'binary:v')");
      var columns = textInput('', 'columns: cf or cf:qual, comma separated');
      var pageSize = numberInput(50);
      var results = element('div', { class: 'k8s-result-scroll' });
      var pager = element('div', { class: 'k8s-pager' });

      // Cursor pagination: each page remembers where it started, and the next
      // one begins just after the last row key shown (a forward-only scan has
      // no cheap offset). A stack of page starts lets Prev walk back.
      var pageStack = [];
      var page = { start: '', inclusive: true };
      var activePrefix = '';

      function newScan() {
        var query = search.value.trim();
        activePrefix = '';
        var start = query;
        if (query.charAt(query.length - 1) === '*') {
          activePrefix = query.slice(0, -1);
          start = activePrefix;
        }
        page = { start: start, inclusive: true };
        pageStack = [];
        load();
      }

      function load() {
        var size = parseInt(pageSize.value, 10) || 50;
        var params = new URLSearchParams();
        params.set('table', table.name);
        if (page.start) {
          params.set('start', page.start);
          params.set('startInclusive', page.inclusive ? 'true' : 'false');
        }
        if (activePrefix) {
          params.set('prefix', activePrefix);
        }
        // Ask for one extra row: getting it back means there is a next page.
        params.set('limit', size + 1);
        if (columns.value.trim()) {
          params.set('columns', columns.value.trim());
        }
        if (filter.value.trim()) {
          params.set('filter', filter.value.trim());
        }
        results.innerHTML = '<div class="k8s-muted">Scanning…</div>';
        pager.innerHTML = '';
        request(UI_API + '/hbase/scan?' + params.toString())
          .then(function (rows) {
            var hasNext = rows.length > size;
            var pageRows = hasNext ? rows.slice(0, size) : rows;
            renderRows(table.name, results, pageRows);
            renderPager(pageRows, hasNext);
          })
          .catch(function (error) {
            showError(results, error);
          });
      }

      function renderPager(pageRows, hasNext) {
        pager.innerHTML = '';
        pager.appendChild(
          button('‹ Prev', 'k8s-pager-button' + (pageStack.length ? '' : ' k8s-disabled'), function () {
            if (!pageStack.length) {
              return;
            }
            page = pageStack.pop();
            load();
          })
        );
        pager.appendChild(
          button(
            'Next ›',
            'k8s-pager-button' + (hasNext && pageRows.length ? '' : ' k8s-disabled'),
            function () {
              if (!hasNext || !pageRows.length) {
                return;
              }
              pageStack.push(page);
              page = { start: pageRows[pageRows.length - 1].rowKey, inclusive: false };
              load();
            }
          )
        );
        pager.appendChild(
          element('span', {
            class: 'k8s-muted',
            text:
              'page ' +
              (pageStack.length + 1) +
              ' · ' +
              pageRows.length +
              ' row' +
              (pageRows.length === 1 ? '' : 's') +
              (hasNext ? ', more' : '')
          })
        );
      }

      search.addEventListener('keydown', function (event) {
        if (event.key === 'Enter') {
          newScan();
        }
      });
      filter.addEventListener('keydown', function (event) {
        if (event.key === 'Enter') {
          newScan();
        }
      });

      viewEl.innerHTML = '';
      viewEl.appendChild(
        element('div', { class: 'k8s-table-head' }, [
          element('h4', { class: 'card-heading simple', text: table.name }),
          tableActions(table)
        ])
      );
      viewEl.appendChild(
        element('div', { class: 'k8s-toolbar' }, [
          element('div', { class: 'k8s-toolbar-item k8s-grow' }, [search]),
          element('div', { class: 'k8s-toolbar-item k8s-grow' }, [filter]),
          element('div', { class: 'k8s-toolbar-item' }, [columns]),
          element('div', { class: 'k8s-toolbar-item' }, [
            element('label', { text: 'Page' }),
            pageSize
          ]),
          button('Scan', 'btn btn-small btn-primary', newScan)
        ])
      );
      viewEl.appendChild(results);
      viewEl.appendChild(pager);
      newScan();
    }

    function renderRows(tableName, host, rows) {
      host.innerHTML = '';
      if (!rows.length) {
        host.appendChild(element('div', { class: 'k8s-muted', text: 'No rows matched.' }));
        return;
      }
      var columns = [];
      rows.forEach(function (row) {
        row.cells.forEach(function (cell) {
          if (columns.indexOf(cell.column) === -1) {
            columns.push(cell.column);
          }
        });
      });
      columns.sort();
      // A very wide row could carry thousands of qualifiers; rendering them all
      // would freeze the page, so cap the visible columns and say so.
      if (columns.length > MAX_HBASE_COLUMNS) {
        host.appendChild(
          element('div', {
            class: 'k8s-muted',
            text: 'Showing first ' + MAX_HBASE_COLUMNS + ' of ' + columns.length + ' columns.'
          })
        );
        columns = columns.slice(0, MAX_HBASE_COLUMNS);
      }

      var header = ['Row key'].concat(columns).concat(['']);
      var table = buildTable(header, rows, function (row) {
        var byColumn = {};
        row.cells.forEach(function (cell) {
          byColumn[cell.column] = cell;
        });
        var cells = [element('span', { class: 'k8s-rowkey', text: row.rowKey })];
        columns.forEach(function (column) {
          var cell = byColumn[column];
          if (!cell) {
            cells.push(text(''));
            return;
          }
          var view = element('span', { class: 'k8s-cell', title: 'Click to edit' }, [
            cell.binary
              ? element('em', { class: 'k8s-muted', text: '⬡ binary' })
              : text(cell.value.length > 140 ? cell.value.substring(0, 140) + '…' : cell.value)
          ]);
          view.addEventListener('click', function () {
            openCell(tableName, row.rowKey, cell);
          });
          cells.push(view);
        });
        cells.push(
          element('span', { class: 'k8s-row-actions' }, [
            button('edit', 'k8s-link', function () {
              openRowEditor(tableName, row);
            }),
            button('delete', 'k8s-link k8s-link-danger', function () {
              confirmDestructive('Delete row ' + row.rowKey + '?', 'delete', function () {
                send(UI_API + '/hbase/row/delete', { table: tableName, row: row.rowKey })
                  .then(function () {
                    toast('Row deleted');
                    openTable(current);
                  })
                  .catch(function (error) {
                    toast(error.message || String(error));
                  });
              });
            })
          ])
        );
        return cells;
      });
      host.appendChild(table);
    }

    /* --------------------------------------------------------- cell editor */

    function openCell(tableName, rowKey, cell) {
      var value = element('textarea', { class: 'k8s-input k8s-textarea' });
      value.value = cell.value;
      if (cell.binary) {
        value.disabled = true;
      }
      var meta = element('div', { class: 'k8s-muted' }, [
        text(rowKey + '  ·  ' + cell.column + '  ·  ' + formatDate(cell.timestamp))
      ]);
      var versions = element('div', {});
      var upload = element('input', { type: 'file' });

      var body = element('div', {}, [
        meta,
        cell.binary
          ? element('div', { class: 'k8s-muted', text: 'Binary value shown Base64, read-only.' })
          : null,
        field('Value', value),
        field('Replace with file (binary)', upload),
        element('div', { class: 'k8s-subhead' }, [
          button('Load versions', 'k8s-link', function () {
            request(
              UI_API + '/hbase/cell/versions?table=' +
                encodeURIComponent(tableName) +
                '&row=' +
                encodeURIComponent(rowKey) +
                '&column=' +
                encodeURIComponent(cell.column) +
                '&versions=20'
            )
              .then(function (list) {
                versions.innerHTML = '';
                versions.appendChild(
                  buildTable(['When', 'Value'], list, function (version) {
                    return [
                      formatDate(version.timestamp),
                      version.binary ? '⬡ binary' : version.value
                    ];
                  })
                );
              })
              .catch(function (error) {
                showError(versions, error);
              });
          })
        ]),
        versions
      ]);

      openModal('Cell', body, [
        {
          label: 'Delete cell',
          class: 'btn btn-small btn-danger',
          action: function (handle) {
            send(UI_API + '/hbase/cell/delete', {
              table: tableName,
              row: rowKey,
              columns: [cell.column]
            })
              .then(function () {
                handle.close();
                toast('Cell deleted');
                openTable(current);
              })
              .catch(function (error) {
                toast(error.message || String(error));
              });
          }
        },
        {
          label: 'Save',
          class: 'btn btn-small btn-primary',
          action: function (handle) {
            var done = function () {
              handle.close();
              toast('Cell saved');
              openTable(current);
            };
            if (upload.files && upload.files.length) {
              var form = new FormData();
              form.append('file', upload.files[0]);
              request(
                UI_API + '/hbase/cell/upload?table=' +
                  encodeURIComponent(tableName) +
                  '&row=' +
                  encodeURIComponent(rowKey) +
                  '&column=' +
                  encodeURIComponent(cell.column),
                { method: 'POST', body: form }
              )
                .then(done)
                .catch(function (error) {
                  toast(error.message || String(error));
                });
              return;
            }
            if (cell.binary) {
              handle.close();
              return;
            }
            var cells = {};
            cells[cell.column] = value.value;
            send(UI_API + '/hbase/row', { table: tableName, row: rowKey, cells: cells })
              .then(done)
              .catch(function (error) {
                toast(error.message || String(error));
              });
          }
        }
      ]);
    }

    /* ---------------------------------------------------------- row editor */

    function openRowEditor(tableName, existingRow) {
      var rowKey = textInput(existingRow ? existingRow.rowKey : '', 'row key');
      if (existingRow) {
        rowKey.disabled = true;
      }
      var list = element('div', {});
      var pairs = [];

      function addPair(column, value) {
        var columnInput = textInput(column, 'cf:qualifier');
        var valueInput = textInput(value, 'value');
        var rowNode = element('div', { class: 'k8s-field-row' }, [
          field('Column', columnInput),
          field('Value', valueInput),
          button('×', 'k8s-link k8s-link-danger', function () {
            list.removeChild(rowNode);
            pairs = pairs.filter(function (pair) {
              return pair.node !== rowNode;
            });
          })
        ]);
        var pair = { node: rowNode, column: columnInput, value: valueInput };
        pairs.push(pair);
        list.appendChild(rowNode);
      }

      if (existingRow) {
        existingRow.cells.forEach(function (cell) {
          if (!cell.binary) {
            addPair(cell.column, cell.value);
          }
        });
      }
      if (!pairs.length) {
        addPair('', '');
      }

      var body = element('div', {}, [
        field('Row key', rowKey),
        list,
        button('+ Add column', 'k8s-link', function () {
          addPair('', '');
        })
      ]);

      openModal(existingRow ? 'Edit row' : 'New row', body, [
        { label: 'Cancel', action: function (handle) { handle.close(); } },
        {
          label: 'Save',
          class: 'btn btn-small btn-primary',
          action: function (handle) {
            var key = rowKey.value.trim();
            if (!key) {
              toast('A row key is required');
              return;
            }
            var cells = {};
            pairs.forEach(function (pair) {
              var column = pair.column.value.trim();
              if (column) {
                cells[column] = pair.value.value;
              }
            });
            if (!Object.keys(cells).length) {
              toast('Add at least one column');
              return;
            }
            send(UI_API + '/hbase/row', { table: tableName, row: key, cells: cells })
              .then(function () {
                handle.close();
                toast('Row saved');
                openTable(current);
              })
              .catch(function (error) {
                toast(error.message || String(error));
              });
          }
        }
      ]);
    }

    /* ------------------------------------------------------ column families */

    function openFamilies(tableName) {
      var list = element('div', { class: 'k8s-muted', text: 'Loading…' });
      var modal = openModal('Column families — ' + tableName, list, [
        {
          label: '+ Add family',
          action: function () {
            openFamilyEditor(tableName, null, function () {
              modal.close();
              openFamilies(tableName);
            });
          }
        },
        { label: 'Close', action: function (handle) { handle.close(); } }
      ]);

      request(UI_API + '/hbase/describe?table=' + encodeURIComponent(tableName))
        .then(function (families) {
          list.innerHTML = '';
          list.className = '';
          families.forEach(function (family) {
            list.appendChild(
              element('div', { class: 'k8s-family-row' }, [
                element('div', {}, [
                  element('strong', { text: family.name }),
                  element('div', { class: 'k8s-muted' }, [
                    text(
                      'versions ' +
                        family.maxVersions +
                        ' · ' +
                        family.compression +
                        ' · bloom ' +
                        family.bloomFilterType +
                        ' · ttl ' +
                        family.timeToLive
                    )
                  ])
                ]),
                element('div', {}, [
                  button('Edit', 'k8s-link', function () {
                    openFamilyEditor(tableName, family, function () {
                      modal.close();
                      openFamilies(tableName);
                    });
                  })
                ])
              ])
            );
          });
        })
        .catch(function (error) {
          showError(list, error);
        });
    }

    function openFamilyEditor(tableName, existing, onSaved) {
      var form = familyForm(existing);
      openModal(existing ? 'Edit family' : 'Add family', form.node, [
        { label: 'Cancel', action: function (handle) { handle.close(); } },
        {
          label: 'Save',
          class: 'btn btn-small btn-primary',
          action: function (handle) {
            var family = form.read();
            if (!family.name) {
              toast('A family name is required');
              return;
            }
            send(existing ? UI_API + '/hbase/family/modify' : UI_API + '/hbase/family/add', {
              table: tableName,
              family: family
            })
              .then(function () {
                handle.close();
                toast('Family saved');
                if (onSaved) {
                  onSaved();
                }
              })
              .catch(function (error) {
                toast(error.message || String(error));
              });
          }
        }
      ]);
    }

    /* ---------------------------------------------------------- new table */

    function openCreateTable() {
      var name = textInput('', 'table name');
      var families = element('div', {});
      var forms = [];

      function addFamily() {
        var form = familyForm(null);
        forms.push(form);
        families.appendChild(element('div', { class: 'k8s-card-inset' }, [form.node]));
      }
      addFamily();

      var body = element('div', {}, [
        field('Table name', name),
        element('div', { class: 'k8s-subhead', text: 'Column families' }),
        families,
        button('+ Add family', 'k8s-link', addFamily)
      ]);

      openModal('New table', body, [
        { label: 'Cancel', action: function (handle) { handle.close(); } },
        {
          label: 'Create',
          class: 'btn btn-small btn-primary',
          action: function (handle) {
            var tableName = name.value.trim();
            if (!tableName) {
              toast('A table name is required');
              return;
            }
            var payload = forms
              .map(function (form) {
                return form.read();
              })
              .filter(function (family) {
                return family.name;
              });
            if (!payload.length) {
              toast('Add at least one column family');
              return;
            }
            send(UI_API + '/hbase/table/create', { table: tableName, families: payload })
              .then(function () {
                handle.close();
                toast('Table created');
                loadTables(tableName);
              })
              .catch(function (error) {
                toast(error.message || String(error));
              });
          }
        }
      ]);
    }

    /* ------------------------------------------------------------ regions */

    function openRegions(tableName) {
      var body = element('div', { class: 'k8s-muted', text: 'Loading…' });
      openModal('Regions — ' + tableName, body, [
        { label: 'Close', action: function (handle) { handle.close(); } }
      ]);
      request(UI_API + '/hbase/regions?table=' + encodeURIComponent(tableName))
        .then(function (regions) {
          body.innerHTML = '';
          body.className = '';
          if (!regions.length) {
            body.appendChild(element('div', { class: 'k8s-muted', text: 'No regions.' }));
            return;
          }
          body.appendChild(
            buildTable(['Region', 'Start key', 'End key'], regions, function (region) {
              return [region.name, region.startKey, region.endKey];
            })
          );
        })
        .catch(function (error) {
          showError(body, error);
        });
    }

    /* -------------------------------------------------------- bulk upload */

    function openBulkUpload(tableName) {
      var file = element('input', { type: 'file', accept: '.csv,text/csv' });
      var body = element('div', {}, [
        element('div', { class: 'k8s-muted' }, [
          text('CSV with a header row. First column is the row key, the rest are cf:qualifier.')
        ]),
        field('CSV file', file)
      ]);
      openModal('Bulk upload — ' + tableName, body, [
        { label: 'Cancel', action: function (handle) { handle.close(); } },
        {
          label: 'Upload',
          class: 'btn btn-small btn-primary',
          action: function (handle) {
            if (!file.files || !file.files.length) {
              toast('Choose a CSV file');
              return;
            }
            var form = new FormData();
            form.append('file', file.files[0]);
            request(UI_API + '/hbase/bulk?table=' + encodeURIComponent(tableName), {
              method: 'POST',
              body: form
            })
              .then(function (count) {
                handle.close();
                toast(count + ' rows written');
                openTable(current);
              })
              .catch(function (error) {
                toast(error.message || String(error));
              });
          }
        }
      ]);
    }

    loadTables(null);
  }

  /* -------------------------------------------------------------------- jobs */

  function initJobs() {
    var list = el('jobList');
    var running = el('runningJobs');
    var pager = el('jobPager');
    var search = el('jobSearch');
    var range = el('jobRange');
    var customRange = el('jobCustomRange');
    var from = el('jobFrom');
    var to = el('jobTo');
    var pageSize = el('jobPageSize');
    var refresh = el('refreshJobs');
    var userFilter = el('jobUserFilter');
    var flinkSearch = el('flinkSearch');
    var flinkState = el('flinkState');

    // Filters persist per application type so returning from a job detail keeps
    // them, instead of resetting to the default screen. The active tab itself is
    // carried in the URL (?type=), which the server also reads to render the
    // right tab first — so there is no flash on load.
    var SPARK_KEY = 'kudos.jobs.spark';
    var FLINK_KEY = 'kudos.jobs.flink';
    function readStore(key) {
      try {
        return JSON.parse(sessionStorage.getItem(key)) || {};
      } catch (error) {
        return {};
      }
    }
    function writeStore(key, value) {
      try {
        sessionStorage.setItem(key, JSON.stringify(value));
      } catch (error) {
        // Storage may be unavailable (private mode); filters just won't persist.
      }
    }
    function saveSpark() {
      writeStore(SPARK_KEY, {
        search: search.value,
        user: userFilter ? userFilter.value : '',
        range: range.value,
        from: from.value,
        to: to.value,
        pageSize: pageSize.value
      });
    }
    function restoreSpark() {
      var s = readStore(SPARK_KEY);
      if (s.search != null) search.value = s.search;
      if (userFilter && s.user != null) userFilter.value = s.user;
      if (s.range) range.value = s.range;
      if (s.from) from.value = s.from;
      if (s.to) to.value = s.to;
      if (s.pageSize) pageSize.value = s.pageSize;
      var isCustom = range.value === 'custom';
      customRange.className = isCustom ? 'k8s-toolbar-item' : 'k8s-toolbar-item k8s-hidden';
    }
    function saveFlink() {
      writeStore(FLINK_KEY, { search: flinkSearch.value, state: flinkState.value });
    }
    function restoreFlink() {
      var f = readStore(FLINK_KEY);
      if (f.search != null) flinkSearch.value = f.search;
      if (f.state) flinkState.value = f.state;
    }

    // Newest run first is the useful default, matching how the history server
    // itself orders the list.
    var state = { applications: [], running: [], sortKey: 'startTime', sortDir: 'desc', page: 1 };

    var columns = [
      { key: 'completed', label: '' },
      { key: 'name', label: 'Application' },
      { key: 'id', label: 'ID' },
      { key: 'user', label: 'User' },
      { key: 'startTime', label: 'Started' },
      { key: 'durationMillis', label: 'Duration' },
      { key: 'sparkVersion', label: 'Spark' },
      { key: null, label: 'Logs' }
    ];

    function formatDuration(millis) {
      if (!millis) {
        return '';
      }
      var seconds = Math.floor(millis / 1000);
      if (seconds < 60) {
        return seconds + 's';
      }
      var minutes = Math.floor(seconds / 60);
      if (minutes < 60) {
        return minutes + 'm ' + (seconds % 60) + 's';
      }
      return Math.floor(minutes / 60) + 'h ' + (minutes % 60) + 'm';
    }

    function formatStart(application) {
      return (application.startTime || '').replace('T', ' ').substring(0, 19);
    }

    function isoDaysAgo(days) {
      var date = new Date(Date.now() - days * 24 * 60 * 60 * 1000);
      return date.toISOString().substring(0, 10);
    }

    /* The history server applies the lower bound, so a narrow range is never
       transferred. The upper bound of a custom range is applied here: the
       screen only ever holds one page of already-fetched rows. */
    function lowerBound() {
      if (range.value === 'custom') {
        return from.value || '';
      }
      return isoDaysAgo(parseInt(range.value, 10));
    }

    function upperBound() {
      return range.value === 'custom' && to.value ? to.value : '';
    }

    function matchesFilters(application) {
      var limit = upperBound();
      if (limit && formatStart(application).substring(0, 10) > limit) {
        return false;
      }
      var needle = search.value.trim().toLowerCase();
      if (!needle) {
        return true;
      }
      var haystack = [
        application.name,
        application.id,
        formatStart(application)
      ]
        .join(' ')
        .toLowerCase();
      return haystack.indexOf(needle) !== -1;
    }

    function compare(left, right) {
      var a = left[state.sortKey];
      var b = right[state.sortKey];
      var order;
      if (typeof a === 'number' && typeof b === 'number') {
        order = a - b;
      } else if (typeof a === 'boolean' && typeof b === 'boolean') {
        order = (a ? 1 : 0) - (b ? 1 : 0);
      } else {
        order = String(a === undefined ? '' : a).localeCompare(
          String(b === undefined ? '' : b)
        );
      }
      return state.sortDir === 'asc' ? order : -order;
    }

    function header() {
      var head = document.createElement('thead');
      var row = document.createElement('tr');
      columns.forEach(function (column) {
        var th = document.createElement('th');
        if (!column.key) {
          th.appendChild(text(column.label));
        } else {
          var link = document.createElement('a');
          link.href = 'javascript:void(0)';
          link.appendChild(text(column.label));
          if (state.sortKey === column.key) {
            var caret = document.createElement('i');
            caret.className =
              'fa fa-fw ' + (state.sortDir === 'asc' ? 'fa-caret-up' : 'fa-caret-down');
            link.appendChild(caret);
          }
          link.addEventListener('click', function () {
            if (state.sortKey === column.key) {
              state.sortDir = state.sortDir === 'asc' ? 'desc' : 'asc';
            } else {
              state.sortKey = column.key;
              state.sortDir = 'asc';
            }
            state.page = 1;
            renderRunning();
            render();
          });
          th.appendChild(link);
        }
        row.appendChild(th);
      });
      head.appendChild(row);
      return head;
    }

    function cells(application) {
      var icon = document.createElement('i');
      icon.className =
        'fa fa-fw ' +
        (application.completed ? 'fa-check-circle k8s-ok' : 'fa-spinner k8s-running');
      icon.title = application.completed ? 'Completed' : 'Running';

      // History owns completed applications. A live Kyuubi engine has no
      // History UI yet, so show it without a broken link.
      var name = document.createElement(application.completed ? 'a' : 'span');
      if (application.completed) {
        name.href = '/jobs/' + encodeURIComponent(application.id);
      }
      name.appendChild(text(application.name));
      var id = document.createElement(application.completed ? 'a' : 'span');
      if (application.completed) {
        id.href = '/jobs/' + encodeURIComponent(application.id);
      }
      id.appendChild(text(application.id));

      var logs = document.createElement('span');
      if (application.completed) {
        logs = document.createElement('a');
        logs.href =
          '/spark-ui/api/v1/applications/' + encodeURIComponent(application.id) + '/logs';
        logs.title = 'Download the event logs of this application';
        var logsIcon = document.createElement('i');
        logsIcon.className = 'fa fa-fw fa-download';
        logs.appendChild(logsIcon);
      }

      return [
        icon,
        name,
        id,
        application.user,
        formatStart(application),
        formatDuration(application.durationMillis),
        application.sparkVersion,
        logs
      ];
    }

    function renderPager(total, pages) {
      pager.innerHTML = '';
      if (!total) {
        return;
      }
      var first = (state.page - 1) * parseInt(pageSize.value, 10) + 1;
      var last = Math.min(state.page * parseInt(pageSize.value, 10), total);

      var summary = document.createElement('span');
      summary.className = 'k8s-muted';
      summary.appendChild(text(first + '–' + last + ' of ' + total));
      pager.appendChild(summary);

      function button(label, target, enabled) {
        var link = document.createElement('a');
        link.className = 'k8s-pager-button' + (enabled ? '' : ' k8s-disabled');
        link.href = 'javascript:void(0)';
        link.appendChild(text(label));
        if (enabled) {
          link.addEventListener('click', function () {
            state.page = target;
            render();
          });
        }
        pager.appendChild(link);
      }

      button('‹ Previous', state.page - 1, state.page > 1);
      var position = document.createElement('span');
      position.className = 'k8s-muted';
      position.appendChild(text('Page ' + state.page + ' of ' + pages));
      pager.appendChild(position);
      button('Next ›', state.page + 1, state.page < pages);
    }

    function render() {
      var filtered = state.applications.filter(matchesFilters).sort(compare);
      var size = parseInt(pageSize.value, 10);
      var pages = Math.max(1, Math.ceil(filtered.length / size));
      if (state.page > pages) {
        state.page = pages;
      }
      var visible = filtered.slice((state.page - 1) * size, state.page * size);

      list.innerHTML = '';
      if (!filtered.length) {
        var empty = document.createElement('div');
        empty.className = 'k8s-muted';
        empty.appendChild(
          text(
            state.applications.length
              ? 'No application matches the current filter.'
              : 'No Spark applications in this period. Run a query in the editor.'
          )
        );
        list.appendChild(empty);
        renderPager(0, 1);
        return;
      }

      var table = document.createElement('table');
      table.className = 'table table-condensed table-huedatatable';
      table.appendChild(header());
      var body = document.createElement('tbody');
      visible.forEach(function (application) {
        var row = document.createElement('tr');
        cells(application).forEach(function (cell) {
          var td = document.createElement('td');
          if (cell instanceof Node) {
            td.appendChild(cell);
          } else {
            td.appendChild(text(cell));
          }
          row.appendChild(td);
        });
        body.appendChild(row);
      });
      table.appendChild(body);
      list.appendChild(table);
      renderPager(filtered.length, pages);
    }

    function renderRunning() {
      var visible = state.running.filter(matchesFilters).sort(compare);
      running.innerHTML = '';
      var title = document.createElement('h3');
      title.className = 'k8s-jobs-section';
      title.appendChild(text('Running (' + visible.length + ')'));
      running.appendChild(title);
      if (!visible.length) {
        var empty = document.createElement('div');
        empty.className = 'k8s-muted';
        empty.appendChild(text('No running Spark applications.'));
        running.appendChild(empty);
        return;
      }
      var table = document.createElement('table');
      table.className = 'table table-condensed table-huedatatable';
      table.appendChild(header());
      var body = document.createElement('tbody');
      visible.forEach(function (application) {
        var row = document.createElement('tr');
        cells(application).forEach(function (cell) {
          var td = document.createElement('td');
          if (cell instanceof Node) {
            td.appendChild(cell);
          } else {
            td.appendChild(text(cell));
          }
          row.appendChild(td);
        });
        body.appendChild(row);
      });
      table.appendChild(body);
      running.appendChild(table);
    }

    function load() {
      list.innerHTML = '<div class="k8s-muted">Loading…</div>';
      pager.innerHTML = '';
      var url = UI_API + '/spark/applications?limit=500';
      var lower = lowerBound();
      if (lower) {
        url += '&minDate=' + encodeURIComponent(lower);
      }
      if (userFilter && userFilter.value.trim()) {
        url += '&user=' + encodeURIComponent(userFilter.value.trim());
      }
      request(url)
        .then(function (applications) {
          state.running = applications.filter(function (application) {
            return !application.completed;
          });
          state.applications = applications.filter(function (application) {
            return application.completed;
          });
          state.page = 1;
          renderRunning();
          render();
        })
        .catch(function (error) {
          showError(list, error);
        });
    }

    search.addEventListener('input', function () {
      state.page = 1;
      saveSpark();
      renderRunning();
      render();
    });
    if (userFilter) {
      userFilter.addEventListener('change', function () {
        saveSpark();
        load();
      });
    }
    pageSize.addEventListener('change', function () {
      state.page = 1;
      saveSpark();
      render();
    });
    range.addEventListener('change', function () {
      var isCustom = range.value === 'custom';
      customRange.className = isCustom ? 'k8s-toolbar-item' : 'k8s-toolbar-item k8s-hidden';
      if (isCustom && !from.value) {
        from.value = isoDaysAgo(7);
        to.value = isoDaysAgo(0);
      }
      saveSpark();
      load();
    });
    from.addEventListener('change', function () {
      saveSpark();
      load();
    });
    to.addEventListener('change', function () {
      saveSpark();
      render();
    });
    refresh.addEventListener('click', load);

    // ---- Spark / Flink tabs ----
    // Flink jobs carry a different attribute set (no owner or Spark version) and
    // their UI lives behind the /flink-ui proxy, so they get their own tab rather
    // than being mixed into the Spark list.
    var sparkTab = el('sparkJobsTab');
    var flinkTab = el('flinkJobsTab');
    var sparkPane = el('sparkJobsPane');
    var flinkPane = el('flinkJobsPane');
    var flinkRunning = el('flinkRunning');
    var flinkListEl = el('flinkList');
    var refreshFlink = el('refreshFlinkJobs');
    var flinkLoaded = false;
    var sparkLoaded = false;

    function showJobsTab(name) {
      var spark = name === 'spark';
      el('sparkJobsTabItem').classList.toggle('active', spark);
      el('flinkJobsTabItem').classList.toggle('active', !spark);
      sparkPane.classList.toggle('active', spark);
      flinkPane.classList.toggle('active', !spark);
      sparkPane.hidden = !spark;
      flinkPane.hidden = spark;
      sparkTab.setAttribute('aria-selected', String(spark));
      flinkTab.setAttribute('aria-selected', String(!spark));
      // Keep the active tab in the URL so a refresh or a return from a job
      // detail stays on it, without reloading the page.
      try {
        var url = new URL(window.location.href);
        url.searchParams.set('type', name);
        window.history.replaceState({}, '', url);
      } catch (error) {
        // history/URL unavailable — the tab still switches, just not bookmarkable.
      }
      // Each tab fetches its data the first time it is shown.
      if (spark && !sparkLoaded) {
        sparkLoaded = true;
        load();
      }
      if (!spark && !flinkLoaded) {
        flinkLoaded = true;
        loadFlink();
      }
    }
    sparkTab.addEventListener('click', function (event) {
      event.preventDefault();
      showJobsTab('spark');
    });
    flinkTab.addEventListener('click', function (event) {
      event.preventDefault();
      showJobsTab('flink');
    });

    // Open the job's Flink dashboard framed in the KUDOS chrome. Running jobs use
    // the live JobManager, finished ones the History Server.
    function flinkUiHref(application) {
      var jid = application.id.replace(/^flink-/, '');
      var scope = application.completed ? 'history' : 'jobmanager';
      return '/flink/' + scope + '/' + encodeURIComponent(jid);
    }

    function flinkTable(applications) {
      var table = document.createElement('table');
      table.className = 'table table-condensed table-huedatatable';
      var head = document.createElement('thead');
      var headRow = document.createElement('tr');
      ['', 'Job', 'ID', 'Started', 'Duration'].forEach(function (label) {
        var th = document.createElement('th');
        th.appendChild(text(label));
        headRow.appendChild(th);
      });
      head.appendChild(headRow);
      table.appendChild(head);
      var body = document.createElement('tbody');
      applications.forEach(function (application) {
        var icon = document.createElement('i');
        icon.className =
          'fa fa-fw ' +
          (application.completed ? 'fa-check-circle k8s-ok' : 'fa-spinner k8s-running');
        icon.title = application.completed ? 'Completed' : 'Running';
        var name = document.createElement('a');
        name.href = flinkUiHref(application);
        name.appendChild(text(application.name));
        var id = document.createElement('a');
        id.href = flinkUiHref(application);
        id.appendChild(text(application.id));
        var row = document.createElement('tr');
        [icon, name, id, formatStart(application), formatDuration(application.durationMillis)].forEach(
          function (cell) {
            var td = document.createElement('td');
            if (cell instanceof Node) {
              td.appendChild(cell);
            } else {
              td.appendChild(text(cell));
            }
            row.appendChild(td);
          }
        );
        body.appendChild(row);
      });
      table.appendChild(body);
      return table;
    }

    function renderFlinkSection(container, title, applications, emptyText) {
      container.innerHTML = '';
      var heading = document.createElement('h3');
      heading.className = 'k8s-jobs-section';
      heading.appendChild(text(title + ' (' + applications.length + ')'));
      container.appendChild(heading);
      if (applications.length) {
        container.appendChild(flinkTable(applications));
      } else {
        var empty = document.createElement('div');
        empty.className = 'k8s-muted';
        empty.appendChild(text(emptyText));
        container.appendChild(empty);
      }
    }

    // The Flink filter is applied client-side over the fetched list: a text
    // needle over job/id/started, plus a running/completed state selector.
    var flinkApps = [];
    function matchesFlink(application) {
      var wanted = flinkState.value;
      if (wanted === 'running' && application.completed) {
        return false;
      }
      if (wanted === 'completed' && !application.completed) {
        return false;
      }
      var needle = flinkSearch.value.trim().toLowerCase();
      if (!needle) {
        return true;
      }
      return [application.name, application.id, formatStart(application)]
        .join(' ')
        .toLowerCase()
        .indexOf(needle) !== -1;
    }

    function renderFlink() {
      var visible = flinkApps.filter(matchesFlink);
      renderFlinkSection(
        flinkRunning,
        'Running',
        visible.filter(function (application) {
          return !application.completed;
        }),
        'No running Flink jobs.'
      );
      var completed = visible.filter(function (application) {
        return application.completed;
      });
      flinkListEl.innerHTML = '';
      if (completed.length) {
        flinkListEl.appendChild(flinkTable(completed));
      } else {
        var empty = document.createElement('div');
        empty.className = 'k8s-muted';
        empty.appendChild(text('No completed Flink jobs.'));
        flinkListEl.appendChild(empty);
      }
    }

    function loadFlink() {
      flinkRunning.innerHTML = '<div class="k8s-muted">Loading running jobs…</div>';
      flinkListEl.innerHTML = '<div class="k8s-muted">Loading…</div>';
      request(UI_API + '/flink/applications')
        .then(function (applications) {
          flinkApps = applications;
          renderFlink();
        })
        .catch(function (error) {
          showError(flinkListEl, error);
        });
    }
    flinkSearch.addEventListener('input', function () {
      saveFlink();
      renderFlink();
    });
    flinkState.addEventListener('change', function () {
      saveFlink();
      renderFlink();
    });
    if (refreshFlink) {
      refreshFlink.addEventListener('click', loadFlink);
    }

    // Restore each tab's saved filters, then open the tab named in the URL (the
    // server already rendered it active), which triggers its first load.
    restoreSpark();
    restoreFlink();
    var initialType =
      new URLSearchParams(window.location.search).get('type') === 'flink' ? 'flink' : 'spark';
    showJobsTab(initialType);
  }

  function pad(value) {
    return (value < 10 ? '0' : '') + value;
  }

  // Counts the Kerberos ticket down and signs the user out when it runs out.
  // The deadline is anchored to page load plus the seconds the server had left,
  // so the browser's own clock offset never shifts it.
  function initTicketTimer() {
    var timer = el('k8s-ticket-timer');
    if (!timer) {
      return;
    }
    var remaining = parseInt(timer.getAttribute('data-remaining'), 10);
    if (isNaN(remaining)) {
      return;
    }
    var output = el('k8s-ticket-remaining');
    var logoutUrl = timer.getAttribute('data-logout');
    var deadline = Date.now() + remaining * 1000;
    var signedOut = false;

    function tick() {
      var left = Math.round((deadline - Date.now()) / 1000);
      if (left <= 0) {
        output.textContent = '00:00:00';
        timer.classList.add('k8s-ticket-expired');
        if (!signedOut) {
          signedOut = true;
          // The session is already worthless; sign out so the ticket is wiped
          // and the login page explains why.
          window.location.href = logoutUrl;
        }
        return;
      }
      // Warn in the last five minutes.
      timer.classList.toggle('k8s-ticket-warning', left <= 300);
      var hours = Math.floor(left / 3600);
      var minutes = Math.floor((left % 3600) / 60);
      var seconds = left % 60;
      output.textContent = pad(hours) + ':' + pad(minutes) + ':' + pad(seconds);
    }

    tick();
    timer.dataset.intervalId = String(setInterval(tick, 1000));
  }

  // A page restored from the back/forward cache keeps its old JavaScript state,
  // so the ticket countdown would go on running from the deadline it had before
  // the user signed out and back in. Safari caches pages this way even when they
  // are marked no-store, so re-fetch from the server on a cached restore: that
  // re-seeds the timer with the new ticket, or bounces a signed-out user to the
  // login page. A normal load is not persisted and is left alone.
  window.addEventListener('pageshow', function (event) {
    if (event.persisted) {
      window.location.reload();
    }
  });

  document.addEventListener('DOMContentLoaded', function () {
    initTicketTimer();
    var app = document.body.getAttribute('data-app');
    if (app === 'editor') {
      initEditor();
    } else if (app === 'filebrowser') {
      initBrowser('hdfs');
    } else if (app === 'ozone') {
      initBrowser('ozone');
    } else if (app === 'hbase') {
      initHbase();
    } else if (app === 'jobs') {
      initJobs();
    }
  });
})();
