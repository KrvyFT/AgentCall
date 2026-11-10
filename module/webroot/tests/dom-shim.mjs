/**
 * 极简 DOM 垫片（无 jsdom 依赖），只实现 module/webroot/app.js 实际用到的接口。
 *
 * 目的：在 node --test 中真实执行 app.js 的启动/渲染/保存按钮状态机，
 * 而不是只测纯函数。所有元素默认按 id 惰性创建；已存在的状态（disabled、hidden、
 * className、textContent）由 app.js 自己驱动。
 */
export function installDom(options) {
  const opts = options || {};
  const elements = new Map();
  const tabs = [];
  const pages = [];
  const documentListeners = {};

  function makeElement(tagName, id) {
    const children = [];
    const listeners = {};
    const attrs = {};
    const element = {
      tagName: String(tagName || "div").toUpperCase(),
      id: id || "",
      children,
      parentNode: null,
      value: "",
      checked: false,
      disabled: false,
      hidden: false,
      className: "",
      style: {},
      type: "",
      list: null,
      get firstChild() {
        return children[0] || null;
      },
      appendChild(child) {
        child.parentNode = element;
        children.push(child);
        return child;
      },
      removeChild(child) {
        const index = children.indexOf(child);
        if (index >= 0) {
          children.splice(index, 1);
          child.parentNode = null;
        }
        return child;
      },
      addEventListener(type, handler) {
        (listeners[type] = listeners[type] || []).push(handler);
      },
      removeEventListener(type, handler) {
        const list = listeners[type] || [];
        const index = list.indexOf(handler);
        if (index >= 0) list.splice(index, 1);
      },
      setAttribute(name, value) {
        attrs[name] = String(value);
      },
      getAttribute(name) {
        return Object.prototype.hasOwnProperty.call(attrs, name) ? attrs[name] : null;
      },
      removeAttribute(name) {
        delete attrs[name];
      },
      focus() {},
      select() {},
      click() {
        const event = { target: element, currentTarget: element };
        (listeners.click || []).slice().forEach((handler) => handler(event));
      },
      dispatch(type, event) {
        (listeners[type] || []).slice().forEach((handler) => handler(event || { target: element }));
      },
      listenerCount(type) {
        return (listeners[type] || []).length;
      },
    };
    // 与真实 DOM 一致：textContent 读取时聚合子节点，写入时清空子节点。
    let ownText = "";
    Object.defineProperty(element, "textContent", {
      configurable: true,
      get() {
        return ownText + children.map((child) => child.textContent).join("");
      },
      set(value) {
        ownText = value === null || value === undefined ? "" : String(value);
        children.length = 0;
      },
    });
    Object.defineProperty(element, "innerHTML", {
      configurable: true,
      get() {
        throw new Error("innerHTML must never be read in the WebUI");
      },
      set() {
        throw new Error("innerHTML must never be written in the WebUI");
      },
    });
    return element;
  }

  function getElementById(id) {
    if (!elements.has(id)) elements.set(id, makeElement(id.indexOf("page-") === 0 ? "section" : "div", id));
    return elements.get(id);
  }

  for (const page of ["overview", "rules", "ai", "dialog", "diag"]) {
    const tab = makeElement("button", "tab-" + page);
    tab.setAttribute("data-page", page);
    tab.className = page === "overview" ? "tab is-active" : "tab";
    tabs.push(tab);
    if (page === "overview") tab.setAttribute("aria-current", "page");

    const section = getElementById("page-" + page);
    section.className = page === "overview" ? "page is-active" : "page";
    pages.push(section);
  }

  function selectAll(selector) {
    if (selector === ".tab") return tabs.slice();
    if (selector === ".page") return pages.slice();
    if (selector === '.tab[data-page="diag"]') return [tabs[4]];
    if (selector === "#page-overview input, #page-rules input, #page-rules select, #page-rules textarea, #page-ai input, #page-dialog input, #page-dialog textarea") {
      return [];
    }
    return [];
  }

  const body = makeElement("body", "body");
  const document = {
    readyState: opts.readyState || "loading",
    body,
    getElementById,
    createElement: (tagName) => makeElement(tagName),
    querySelector: (selector) => selectAll(selector)[0] || null,
    querySelectorAll: (selector) => selectAll(selector),
    addEventListener(type, handler) {
      (documentListeners[type] = documentListeners[type] || []).push(handler);
    },
    dispatch(type) {
      (documentListeners[type] || []).slice().forEach((handler) => handler({ type }));
    },
  };

  const storage = new Map();
  const window = {
    ksu: opts.ksu,
    AgentCallBridge: opts.bridge,
    localStorage: {
      getItem(key) {
        return storage.has(key) ? storage.get(key) : null;
      },
      setItem(key, value) {
        storage.set(key, String(value));
      },
      removeItem(key) {
        storage.delete(key);
      },
    },
  };

  return { document, window, elements, tabs, pages, body, storage };
}

export function installGlobalDom(dom) {
  globalThis.window = dom.window;
  globalThis.document = dom.document;
}
