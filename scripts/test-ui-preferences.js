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

class Element {
  constructor(attributes = {}) {
    this.attributes = { ...attributes };
    this.hidden = false;
    this.checked = false;
    this.listeners = {};
  }

  getAttribute(name) {
    return this.attributes[name] || null;
  }

  setAttribute(name, value) {
    this.attributes[name] = String(value);
  }

  addEventListener(name, listener) {
    this.listeners[name] = listener;
  }

  dispatch(name) {
    this.listeners[name]({ target: this });
  }

  focus() {}
}

function runPreferences(user, saved, availableTools) {
  const body = new Element({ 'data-ui-user': user, 'data-app': '' });
  const modeChoices = ['old', 'modern'].map((value) => new Element({ 'data-ui-mode-choice': value }));
  const themeChoices = ['light', 'dark', 'system'].map((value) => new Element({ 'data-ui-theme-choice': value }));
  const toolNodes = availableTools.map((tool) => new Element({ 'data-ui-tool': tool }));
  const toolSettings = availableTools.map((tool) => new Element({ 'data-ui-tool-setting': tool }));
  const toolChoices = availableTools.map((tool) => new Element({ 'data-ui-tool-choice': tool }));
  const byId = {
    uiSettingsButton: new Element(),
    uiSettingsPanel: new Element(),
    uiSettingsClose: new Element(),
    uiSettingsReset: new Element()
  };
  const storage = new Map(saved ? [[`kudos.ui-preferences.v1.${user}`, JSON.stringify(saved)]] : []);
  const selectorMap = {
    '[data-ui-mode-choice]': modeChoices,
    '[data-ui-theme-choice]': themeChoices,
    '[data-ui-tool]': toolNodes,
    '[data-ui-tool-choice]': toolChoices
  };
  const document = {
    body,
    documentElement: { style: {} },
    addEventListener(name, listener) {
      if (name === 'DOMContentLoaded') {
        this.ready = listener;
      }
    },
    getElementById(id) {
      return byId[id] || null;
    },
    querySelectorAll(selector) {
      return selectorMap[selector] || [];
    },
    querySelector(selector) {
      const match = /^\[data-ui-(tool|tool-setting|tool-choice)="(.+)"\]$/.exec(selector);
      if (!match) return null;
      const name = `data-ui-${match[1]}`;
      return [...toolNodes, ...toolSettings, ...toolChoices].find((node) => node.getAttribute(name) === match[2]) || null;
    }
  };
  const context = {
    document,
    window: {
      localStorage: {
        getItem: (key) => storage.has(key) ? storage.get(key) : null,
        setItem: (key, value) => storage.set(key, value)
      },
      matchMedia: () => ({ matches: true, addEventListener() {} }),
      addEventListener() {}
    },
    console,
    JSON,
    Array,
    setInterval() {},
    clearInterval() {}
  };
  vm.runInNewContext(source, context);
  document.ready();
  return { body, document, storage, toolNodes, toolSettings, toolChoices, byId };
}

const alice = runPreferences('alice', { mode: 'modern', theme: 'dark', tools: { ozone: false } }, ['editor', 'files', 'ozone', 'jobs']);
assert.equal(alice.body.getAttribute('data-ui-mode'), 'modern');
assert.equal(alice.body.getAttribute('data-ui-theme'), 'dark');
assert.equal(alice.toolNodes.find((node) => node.getAttribute('data-ui-tool') === 'ozone').hidden, true);
assert.equal(alice.toolSettings.length, 4, 'Недоступный сервером инструмент не должен попадать в настройки');
assert.equal(alice.document.documentElement.style.colorScheme, 'dark');

alice.byId.uiSettingsReset.dispatch('click');
assert.equal(alice.toolNodes.find((node) => node.getAttribute('data-ui-tool') === 'ozone').hidden, false);
assert.equal(JSON.parse(alice.storage.get('kudos.ui-preferences.v1.alice')).mode, 'old');

const bob = runPreferences('bob', null, ['editor', 'files', 'ozone', 'jobs']);
assert.equal(bob.body.getAttribute('data-ui-mode'), 'old');
assert.equal(bob.body.getAttribute('data-ui-theme'), 'system');
assert.ok(bob.toolNodes.every((node) => !node.hidden), 'Без сохранённых настроек инструменты должны быть видимы');

assert.match(styles, /body\[data-ui-mode="modern"\][\s\S]*-apple-system/,
  'Modern-слой не использует системную типографику');
assert.match(styles, /body\[data-ui-mode="modern"\]\[data-ui-theme="system"\]\[data-ui-system-theme="dark"\]/,
  'System-тема не следует системной тёмной теме');
assert.doesNotMatch(styles, /k8s-modern-window-chrome/,
  'Modern-слой не должен содержать оконную панель');
assert.doesNotMatch(styles, /apple-logo|apple\.png|apple\.svg/i,
  'Modern-слой не должен содержать Apple asset');
console.log('PASS UI preferences are user-scoped and keep server feature boundaries');
