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

    var exportBtn = el('exportExcel');

    function exportExcel() {
      var sql = query.value.trim();
      if (!sql) {
        return;
      }
      exportBtn.disabled = true;
      fetch('/api/sql/export', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sql: sql })
      })
        .then(function (response) {
          if (!response.ok) {
            return response.text().then(function (body) {
              throw new Error(body || 'HTTP ' + response.status);
            });
          }
          return response.blob();
        })
        .then(function (blob) {
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

    run.addEventListener('click', execute);
    exportBtn.addEventListener('click', exportExcel);
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
    var refresh = el('refreshBrowser');
    var newFolderBtn = el('newFolder');
    var uploadInput = el('uploadFile');
    var base = '/api/' + kind;
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
      request('/api/hbase/tables')
        .then(function (tables) {
          tablesEl.innerHTML = '';
          if (!tables.length) {
            tablesEl.appendChild(element('li', { class: 'k8s-muted', text: 'No tables.' }));
            return;
          }
          tables.forEach(function (table) {
            var link = element('a', { href: 'javascript:void(0)' }, [
              element('span', { text: table.name }),
              table.enabled ? null : element('span', { class: 'k8s-badge-off', text: 'disabled' })
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

    function lifecycle(url, body, message) {
      send(url, body)
        .then(function () {
          toast(message);
          loadTables(current ? current.name : null);
        })
        .catch(function (error) {
          toast(error.message || String(error));
        });
    }

    function tableActions(table) {
      var toggle = table.enabled
        ? button('Disable', 'btn btn-small', function () {
            lifecycle('/api/hbase/table/disable', { table: table.name }, 'Table disabled');
          })
        : button('Enable', 'btn btn-small', function () {
            lifecycle('/api/hbase/table/enable', { table: table.name }, 'Table enabled');
          });
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
        toggle,
        button('Truncate', 'btn btn-small', function () {
          confirmDestructive(
            'Truncate ' + table.name + '? Every row is permanently deleted.',
            'truncate',
            function () {
              lifecycle(
                '/api/hbase/table/truncate',
                { table: table.name, preserveSplits: true },
                'Table truncated'
              );
            }
          );
        }),
        button('Drop', 'btn btn-small btn-danger', function () {
          confirmDestructive('Drop table ' + table.name + '? This cannot be undone.', 'drop', function () {
            send('/api/hbase/table/delete', { table: table.name })
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
        request('/api/hbase/scan?' + params.toString())
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
                send('/api/hbase/row/delete', { table: tableName, row: row.rowKey })
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
              '/api/hbase/cell/versions?table=' +
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
            send('/api/hbase/cell/delete', {
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
                '/api/hbase/cell/upload?table=' +
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
            send('/api/hbase/row', { table: tableName, row: rowKey, cells: cells })
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
            send('/api/hbase/row', { table: tableName, row: key, cells: cells })
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

      request('/api/hbase/describe?table=' + encodeURIComponent(tableName))
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
                  }),
                  button('Delete', 'k8s-link k8s-link-danger', function () {
                    confirmDestructive(
                      'Delete family ' + family.name + '? Its data is lost.',
                      'delete',
                      function () {
                      send('/api/hbase/family/delete', {
                        table: tableName,
                        family: family.name
                      })
                        .then(function () {
                          toast('Family deleted');
                          modal.close();
                          openFamilies(tableName);
                        })
                        .catch(function (error) {
                          toast(error.message || String(error));
                        });
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
            send(existing ? '/api/hbase/family/modify' : '/api/hbase/family/add', {
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
            send('/api/hbase/table/create', { table: tableName, families: payload })
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
      request('/api/hbase/regions?table=' + encodeURIComponent(tableName))
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
            request('/api/hbase/bulk?table=' + encodeURIComponent(tableName), {
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
