/* Somena: русский и немецкий (спека 0012, дополнение от 02.10.2026).
   Словарь каждая страница задаёт сама в window.SOMENA_I18N. Выбор языка:
   сохранённый выбор посетителя, потом подсказка сервера по IP (/lang.js:
   немецкие адреса получают немецкий), потом язык браузера, иначе русский. */
(function () {
  'use strict';

  var LANGS = ['ru', 'de'];
  var COOKIE = 'somena-lang';
  var DOMAIN = '.elunaris-vitrail.strangled.net';

  function readCookie(name) {
    var m = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
    return m ? decodeURIComponent(m[1]) : null;
  }

  /* Печенье кладём на все поддомены сразу: выбор языка общий для всего сайта */
  function writeCookie(lang) {
    var shared = location.hostname.indexOf(DOMAIN) !== -1 ? '; domain=' + DOMAIN : '';
    document.cookie = COOKIE + '=' + lang + '; path=/; max-age=31536000; samesite=lax' + shared;
  }

  function browserLang() {
    var tags = navigator.languages || [navigator.language || ''];
    for (var i = 0; i < tags.length; i++) {
      var t = String(tags[i] || '').toLowerCase();
      if (t.indexOf('de') === 0) return 'de';
      if (t.indexOf('ru') === 0) return 'ru';
    }
    return null;
  }

  function initialLang() {
    var saved = readCookie(COOKIE);
    if (saved && LANGS.indexOf(saved) !== -1) return saved;
    if (window.SOMENA_IP_LANG === 'de') return 'de';
    return browserLang() || 'ru';
  }

  function apply(lang) {
    var dict = (window.SOMENA_I18N || {})[lang] || {};
    var nodes = document.querySelectorAll('[data-i18n]');
    for (var i = 0; i < nodes.length; i++) {
      var value = dict[nodes[i].getAttribute('data-i18n')];
      if (value != null) nodes[i].textContent = value;
    }
    var fields = document.querySelectorAll('[data-i18n-ph]');
    for (var f = 0; f < fields.length; f++) {
      var hint = dict[fields[f].getAttribute('data-i18n-ph')];
      if (hint != null) fields[f].setAttribute('placeholder', hint);
    }
    document.documentElement.lang = lang;
    if (dict['__title']) document.title = dict['__title'];
    var buttons = document.querySelectorAll('[data-lang-btn]');
    for (var j = 0; j < buttons.length; j++) {
      var active = buttons[j].getAttribute('data-lang-btn') === lang;
      buttons[j].classList.toggle('active', active);
      buttons[j].setAttribute('aria-pressed', active ? 'true' : 'false');
    }
  }

  apply(initialLang());

  document.addEventListener('click', function (e) {
    if (!e.target || !e.target.closest) return;
    var btn = e.target.closest('[data-lang-btn]');
    if (!btn) return;
    var lang = btn.getAttribute('data-lang-btn');
    writeCookie(lang);
    apply(lang);
  });
})();
