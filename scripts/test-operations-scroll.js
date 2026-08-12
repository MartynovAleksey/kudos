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

#!/usr/bin/env node

const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('src/main/resources/app-static/kudos.js', 'utf8');
const styles = fs.readFileSync('src/main/resources/app-static/kudos.css', 'utf8');
const start = source.indexOf('    function renderOperations(');
const end = source.indexOf('\n\n    function rerunOperation(', start);
assert.notEqual(start, -1, 'Не найдена функция renderOperations');
assert.notEqual(end, -1, 'Не найдена граница функции renderOperations');

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
assert.ok(updatedScroll, 'Список операций не был отрисован');
assert.equal(updatedScroll.scrollTop, 173, 'Позиция прокрутки сброшена при обновлении');
console.log('PASS Operations scroll position is preserved');

const outputStart = source.indexOf('    function showOutputTab(');
const outputEnd = source.indexOf('\n\n    outputTabs.forEach', outputStart);
assert.notEqual(outputStart, -1, 'Не найдена функция showOutputTab');
assert.notEqual(outputEnd, -1, 'Не найдена граница функции showOutputTab');

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
assert.equal(resultOutput.pane.hidden, true, 'Result не скрыт при открытии Operations');
assert.equal(logOutput.pane.hidden, true, 'Log не скрыт при открытии Operations');
assert.equal(operationsOutput.pane.hidden, false, 'Operations не показан в собственной вкладке');
assert.equal(operationsOutput.pane.attributes['aria-hidden'], 'false');
console.log('PASS Output tabs hide inactive panes');

const sessionsStart = source.indexOf('    function renderSessions(');
const sessionsEnd = source.indexOf('\n\n    function sessionForm(', sessionsStart);
assert.notEqual(sessionsStart, -1, 'Не найдена функция renderSessions');
assert.notEqual(sessionsEnd, -1, 'Не найдена граница функции renderSessions');
const sessionTabs = source.slice(sessionsStart, sessionsEnd);
assert.doesNotMatch(sessionTabs, /kyuubiSessionId|k8s-session-id/,
  'Идентификатор Kyuubi не должен отображаться в имени вкладки');
assert.match(sessionTabs, /k8s-session-indicator/, 'На вкладке нет индикатора состояния');
console.log('PASS Session tabs show a status indicator without session ID');

const resultStart = source.indexOf('    function storedResult(');
const resultEnd = source.indexOf('\n\n    function renderEmptyResult', resultStart);
assert.notEqual(resultStart, -1, 'Не найдена функция storedResult');
assert.notEqual(resultEnd, -1, 'Не найдена граница функций хранения результата');

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
  'Результат не восстановлен после перехода между инструментами'
);
const restored = resultContext();
restored.forgetResult('session-1');
assert.equal(restored.storedResult('session-1'), null, 'Очищенный результат остался в хранилище');
assert.match(
  source,
  /query\.value = storedQuery\(activeSessionId\);\s+restoreResult\(\);/,
  'При открытии редактора результат не восстанавливается'
);
console.log('PASS Editor result is retained during tool navigation');

assert.match(source, /data-sql-engine/, 'В редакторе отсутствуют вкладки SQL-движков');
assert.match(source, /engineTabs\.forEach\(function \(tab\) \{\s+tab\.addEventListener\('click'/,
  'Вкладки SQL-движков не переключаются по клику');
assert.match(source, /tab\.parentElement\.classList\.toggle\('active', active\)/,
  'Активная вкладка SQL-движка не оформляется как вкладка Jobs');
assert.match(styles, /\.k8s-session-bar\.k8s-hidden\s*\{\s*display:\s*none;/,
  'Панель Kyuubi-сессий остаётся видимой во вкладке Trino');
assert.match(source, /UI_API \+ '\/trino\/history'/,
  'История запросов Trino не загружается');
assert.match(source, /logsTabItem'\)\.classList\.toggle\('k8s-hidden', logs\.length === 0\)/,
  'Вкладка логов Trino не зависит от логов драйвера');
console.log('PASS Editor engine tabs switch with the Jobs tab pattern');
