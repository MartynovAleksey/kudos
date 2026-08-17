#!/usr/bin/env node

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

const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('src/main/resources/app-static/kudos.js', 'utf8');
const styles = fs.readFileSync('src/main/resources/app-static/kudos.css', 'utf8');
const start = source.indexOf('    function renderOperations(');
const end = source.indexOf('\n\n    function rerunOperation(', start);
assert.notEqual(start, -1, 'renderOperations function was not found');
assert.notEqual(end, -1, 'renderOperations function boundary was not found');

class Element {
  constructor(tagName, attributes = {}) {
    this.tagName = tagName;
    this.attributes = attributes;
    this.children = [];
    this.className = attributes.class || '';
    this.scrollTop = 0;
    this.hidden = false;
    this.classList = {
      toggle: (name, enabled) => {
        const classes = new Set(this.className.split(/\s+/).filter(Boolean));
        if (enabled) {
          classes.add(name);
        } else {
          classes.delete(name);
        }
        this.className = [...classes].join(' ');
      }
    };
  }

  appendChild(child) {
    this.children.push(child);
    return child;
  }

  setAttribute(name, value) {
    this.attributes[name] = String(value);
  }

  querySelector(selector) {
    if (selector === '.k8s-operations-scroll' && this.className.includes('k8s-operations-scroll')) {
      return this;
    }
    for (const child of this.children) {
      if (child instanceof Element) {
        const result = child.querySelector(selector);
        if (result) {
          return result;
        }
      }
    }
    return null;
  }

  set innerHTML(value) {
    this.children = [];
    this._innerHTML = value;
  }

  get innerHTML() {
    return this._innerHTML || '';
  }
}

const operationsContainer = new Element('div');
const context = {
  UI_API: '/ui-api',
  document: { createElement: (tagName) => new Element(tagName) },
  encodeURIComponent,
  element: (tagName, attributes) => new Element(tagName, attributes),
  formatDate: () => '',
  sessionApi: (path) => '/ui-api/sessions' + path,
  text: (value) => ({ textContent: String(value) }),
  button: (label) => new Element('button', { text: label }),
  query: { value: '' },
  saveQuery: () => {},
  sessionMonitorOperations: operationsContainer,
  toast: () => {}
};

vm.runInNewContext(source.slice(start, end), context);

const oldScroll = new Element('div', { class: 'k8s-operations-scroll' });
oldScroll.scrollTop = 173;
operationsContainer.appendChild(oldScroll);

context.renderOperations(
  { id: 'session-1' },
  [
    {
      id: 'operation-1',
      state: 'FINISHED_STATE',
      statement: 'SELECT 1',
      executionCount: 1,
      startedAtEpochMs: 0,
      error: ''
    },
    {
      id: 'operation-2',
      state: 'FINISHED_STATE',
      statement: 'SELECT 2',
      executionCount: 1,
      startedAtEpochMs: 0,
      error: ''
    }
  ]
);

const updatedScroll = operationsContainer.querySelector('.k8s-operations-scroll');
assert.ok(updatedScroll, 'Operations list was not rendered');
assert.equal(updatedScroll.scrollTop, 173, 'Scroll position was reset during refresh');
console.log('PASS Operations scroll position is preserved');

const outputStart = source.indexOf('    function showOutputTab(');
const outputEnd = source.indexOf('\n\n    outputTabs.forEach', outputStart);
assert.notEqual(outputStart, -1, 'showOutputTab function was not found');
assert.notEqual(outputEnd, -1, 'showOutputTab function boundary was not found');

const resultOutput = { tab: new Element('a'), item: new Element('li'), pane: new Element('div') };
const logOutput = { tab: new Element('a'), item: new Element('li'), pane: new Element('div') };
const operationsOutput = { tab: new Element('a'), item: new Element('li'), pane: new Element('div') };
const outputContext = {
  outputTabs: [
    { name: 'results', ...resultOutput },
    { name: 'logs', ...logOutput },
    { name: 'operations', ...operationsOutput }
  ]
};
vm.runInNewContext(source.slice(outputStart, outputEnd), outputContext);
outputContext.showOutputTab('operations');
assert.equal(resultOutput.pane.hidden, true, 'Result is not hidden when Operations opens');
assert.equal(logOutput.pane.hidden, true, 'Log is not hidden when Operations opens');
assert.equal(operationsOutput.pane.hidden, false, 'Operations is not shown in its own tab');
assert.equal(operationsOutput.pane.attributes['aria-hidden'], 'false');
console.log('PASS Output tabs hide inactive panes');

const sessionsStart = source.indexOf('    function renderSessions(');
const sessionsEnd = source.indexOf('\n\n    function sessionForm(', sessionsStart);
assert.notEqual(sessionsStart, -1, 'renderSessions function was not found');
assert.notEqual(sessionsEnd, -1, 'renderSessions function boundary was not found');
const sessionTabs = source.slice(sessionsStart, sessionsEnd);
assert.doesNotMatch(sessionTabs, /kyuubiSessionId|k8s-session-id/,
  'The Kyuubi identifier must not be displayed in the tab name');
assert.match(sessionTabs, /k8s-session-indicator/, 'The tab has no status indicator');
assert.match(sessionTabs, /perQueryTab\.addEventListener\('click', deactivateSession\)/,
  'The per-query tab is not selectable across its full area');
assert.match(sessionTabs, /tab\.addEventListener\('click', function \(\) \{ activateSession\(session\); \}\)/,
  'The session tab is not selectable across its full area');
assert.match(sessionTabs, /event\.stopPropagation\(\);\s+restartSession\(session\);/,
  'Restarting a session must remain a separate action');
assert.match(sessionTabs, /button\('Restart', 'k8s-session-tab-action k8s-session-tab-restart'/,
  'The tab has no explicit restart button');
console.log('PASS Session tabs show a status indicator without session ID');

const resultStart = source.indexOf('    function storedResult(');
const resultEnd = source.indexOf('\n\n    function renderEmptyResult', resultStart);
assert.notEqual(resultStart, -1, 'storedResult function was not found');
assert.notEqual(resultEnd, -1, 'Result-storage function boundary was not found');

const storage = new Map();
const sessionStorage = {
  getItem: (key) => storage.has(key) ? storage.get(key) : null,
  setItem: (key, value) => storage.set(key, value),
  removeItem: (key) => storage.delete(key)
};

function resultContext() {
  const resultState = {
    JSON,
    sessionStorage,
    resultsBySession: Object.create(null),
    resultKeyPrefix: 'kudos.sql.result.analyst.'
  };
  vm.runInNewContext(source.slice(resultStart, resultEnd), resultState);
  return resultState;
}

const result = { columns: ['answer'], rows: [[42]] };
resultContext().rememberResult('session-1', result);
assert.deepEqual(
  JSON.parse(JSON.stringify(resultContext().storedResult('session-1'))),
  result,
  'The result was not restored after navigating between tools'
);
const restored = resultContext();
restored.forgetResult('session-1');
assert.equal(restored.storedResult('session-1'), null, 'The cleared result remains in storage');
assert.match(
  source,
  /query\.value = storedQuery\(activeSessionId\);\s+restoreResult\(\);/,
  'The result is not restored when opening the editor'
);
console.log('PASS Editor result is retained during tool navigation');

assert.match(source, /function loadSessionResult\(session\)/,
  'The Kyuubi session result is not restored after returning to Editor');
assert.match(source, /loadSessionResult\(active\);/,
  'The server-stored result is not loaded for the active Kyuubi session');
assert.match(source, /\/result'\), \{ method: 'DELETE' \}/,
  'Clearing the result does not remove the stored Kyuubi session result');
console.log('PASS Completed Kyuubi result is restored after navigation');

assert.match(styles, /\.k8s-session-perquery \.k8s-session-tab-label\s*\{\s*width: 100%;[\s\S]*pointer-events: none;/,
  'The per-query click target does not cover the entire tab');
assert.match(styles, /\.k8s-session-tab\s*\{[\s\S]*cursor: pointer;/,
  'The session tab is not marked as fully clickable');
console.log('PASS Session tab click targets cover the entire tab');

assert.match(source, /data-sql-engine/, 'The editor has no SQL-engine tabs');
assert.match(source, /engineTabs\.forEach\(function \(tab\) \{\s+tab\.addEventListener\('click'/,
  'SQL-engine tabs do not switch when clicked');
assert.match(source, /tab\.parentElement\.classList\.toggle\('active', active\)/,
  'The active SQL-engine tab is not styled like the Jobs tab');
assert.match(styles, /\.k8s-session-bar\.k8s-hidden\s*\{\s*display:\s*none;/,
  'The Kyuubi session panel remains visible on the Trino tab');
assert.match(source, /UI_API \+ '\/sql\/history\?engine=' \+ encodeURIComponent\(engine\)/,
  'The SQL-engine query history is not loaded');
assert.match(source, /logsTabItem'\)\.classList\.toggle\('k8s-hidden', !supportsSessions && !hasHistory\)/,
  'The log tab is not hidden only for engines without logs');
console.log('PASS Editor engine tabs switch with the Jobs tab pattern');
