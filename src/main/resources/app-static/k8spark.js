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
 * Screen behaviour for the k8spark-ui pages. Every call goes to this
 * application's own Spring API under the browser's authenticated session.
 */
(function () {
  'use strict';

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

  function request(url, options) {
    return fetch(url, Object.assign({ credentials: 'same-origin' }, options || {})).then(
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

  /* ------------------------------------------------------------------ editor */

  function initEditor() {
    var run = el('executeQuery');
    var query = el('queryField');
    var results = el('queryResults');

    function execute() {
      var sql = query.value.trim();
      if (!sql) {
        return;
      }
      results.innerHTML = '<div class="k8s-muted">Executing…</div>';
      run.disabled = true;
      request('/api/sql/execute', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sql: sql })
      })
        .then(function (result) {
          results.innerHTML = '';
          if (!result.rows.length) {
            var empty = document.createElement('div');
            empty.className = 'k8s-muted';
            empty.appendChild(text('The query returned no rows.'));
            results.appendChild(empty);
            return;
          }
          var scroll = document.createElement('div');
          scroll.className = 'k8s-result-scroll';
          scroll.appendChild(buildTable(result.columns, result.rows));
          results.appendChild(scroll);
        })
        .catch(function (error) {
          showError(results, error);
        })
        .then(function () {
          run.disabled = false;
        });
    }

    run.addEventListener('click', execute);
    query.addEventListener('keydown', function (event) {
      if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
        execute();
      }
    });
  }

  /* ------------------------------------------------------- storage browsers */

  function initBrowser(kind) {
    var listing = el('listing');
    var breadcrumbs = el('breadcrumbs');
    var preview = el('preview');
    var base = '/api/' + kind;

    function navigate(path) {
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
              ['', 'Name', 'Size', 'User', 'Group', 'Permissions', 'Date'],
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
                  formatDate(entry.modificationTime)
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

    navigate(listing.getAttribute('data-path') || '/');
  }

  /* ------------------------------------------------------------------- hbase */

  function initHbase() {
    var tables = el('tableList');
    var rows = el('tableRows');

    function loadRows(name, item) {
      Array.prototype.forEach.call(tables.querySelectorAll('li'), function (li) {
        li.className = li === item ? 'active' : '';
      });
      rows.innerHTML = '<div class="k8s-muted">Scanning…</div>';
      request('/api/hbase/scan?table=' + encodeURIComponent(name) + '&limit=50')
        .then(function (scanned) {
          rows.innerHTML = '';
          if (!scanned.length) {
            var empty = document.createElement('div');
            empty.className = 'k8s-muted';
            empty.appendChild(text('The table is empty.'));
            rows.appendChild(empty);
            return;
          }
          var columns = [];
          scanned.forEach(function (row) {
            Object.keys(row.cells).forEach(function (cell) {
              if (columns.indexOf(cell) === -1) {
                columns.push(cell);
              }
            });
          });
          var scroll = document.createElement('div');
          scroll.className = 'k8s-result-scroll';
          scroll.appendChild(
            buildTable(['Row key'].concat(columns), scanned, function (row) {
              return [row.rowKey].concat(
                columns.map(function (column) {
                  return row.cells[column] === undefined ? '' : row.cells[column];
                })
              );
            })
          );
          rows.appendChild(scroll);
        })
        .catch(function (error) {
          showError(rows, error);
        });
    }

    request('/api/hbase/tables')
      .then(function (names) {
        tables.innerHTML = '';
        if (!names.length) {
          var empty = document.createElement('li');
          empty.className = 'k8s-muted';
          empty.appendChild(text('No tables.'));
          tables.appendChild(empty);
          return;
        }
        names.forEach(function (name) {
          var item = document.createElement('li');
          var link = document.createElement('a');
          link.href = 'javascript:void(0)';
          link.appendChild(text(name));
          link.addEventListener('click', function () {
            loadRows(name, item);
          });
          item.appendChild(link);
          tables.appendChild(item);
        });
      })
      .catch(function (error) {
        showError(tables, error);
      });
  }

  /* -------------------------------------------------------------------- jobs */

  function initJobs() {
    var list = el('jobList');
    var pager = el('jobPager');
    var search = el('jobSearch');
    var range = el('jobRange');
    var customRange = el('jobCustomRange');
    var from = el('jobFrom');
    var to = el('jobTo');
    var pageSize = el('jobPageSize');
    var refresh = el('refreshJobs');

    // Newest run first is the useful default, matching how the history server
    // itself orders the list.
    var state = { applications: [], sortKey: 'startTime', sortDir: 'desc', page: 1 };

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
        application.user,
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

      // Both the name and the id open the proxied Spark UI for this
      // application, the way the job browser drills into a run.
      var name = document.createElement('a');
      name.href = '/jobs/' + encodeURIComponent(application.id);
      name.appendChild(text(application.name));
      var id = document.createElement('a');
      id.href = '/jobs/' + encodeURIComponent(application.id);
      id.appendChild(text(application.id));

      var logs = document.createElement('a');
      logs.href =
        '/spark-ui/api/v1/applications/' + encodeURIComponent(application.id) + '/logs';
      logs.title = 'Download the event logs of this application';
      var logsIcon = document.createElement('i');
      logsIcon.className = 'fa fa-fw fa-download';
      logs.appendChild(logsIcon);

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

    function load() {
      list.innerHTML = '<div class="k8s-muted">Loading…</div>';
      pager.innerHTML = '';
      var url = '/api/spark/applications?limit=500';
      var lower = lowerBound();
      if (lower) {
        url += '&minDate=' + encodeURIComponent(lower);
      }
      request(url)
        .then(function (applications) {
          state.applications = applications;
          state.page = 1;
          render();
        })
        .catch(function (error) {
          showError(list, error);
        });
    }

    // Opening on this user's own runs mirrors the job browser this screen
    // replaces; clearing the box shows everyone's.
    search.value = search.getAttribute('data-default-user') || '';

    search.addEventListener('input', function () {
      state.page = 1;
      render();
    });
    pageSize.addEventListener('change', function () {
      state.page = 1;
      render();
    });
    range.addEventListener('change', function () {
      var isCustom = range.value === 'custom';
      customRange.className = isCustom ? 'k8s-toolbar-item' : 'k8s-toolbar-item k8s-hidden';
      if (isCustom && !from.value) {
        from.value = isoDaysAgo(7);
        to.value = isoDaysAgo(0);
      }
      load();
    });
    from.addEventListener('change', load);
    to.addEventListener('change', render);
    refresh.addEventListener('click', load);

    load();
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
