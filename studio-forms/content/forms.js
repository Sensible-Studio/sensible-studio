/*
 * Sensible Studio Forms — the form on a page: validation as the visitor types, the submission, what happens
 * after. No framework; one script per page, every form on it.
 *
 * Markup contract (the components render it):
 *   form.studio-form[data-studio-form=<path>][data-studio-after=message|redirect][data-studio-message][data-studio-redirect]
 *     .studio-form__field[data-studio-field=<name>]  > input|select|textarea[name]  + p[data-studio-error]
 *     fieldset[data-studio-field][data-studio-required]  (radio groups)
 *     [data-studio-messages="<required>|<type/pattern/range>"]   the author's own messages, optional
 *     p[data-studio-status]
 *   data-studio-preview="true" on the author: validated, shown as sent, never stored.
 */
(function () {
  'use strict';

  var DEFAULTS = {
    required: 'This field is required.',
    email: 'Enter a valid email address.',
    pattern: 'This value does not have the expected format.',
    minLength: 'Enter at least {n} characters.',
    maxLength: 'Enter at most {n} characters.',
    min: 'Enter a number of at least {n}.',
    max: 'Enter a number of at most {n}.',
    number: 'Enter a number.',
    date: 'Enter a date.',
    minDate: 'Enter a date from {d} on.',
    maxDate: 'Enter a date up to {d}.',
    fileSize: 'The file is larger than {n} MB.',
    fileType: 'This kind of file is not accepted.',
    sent: 'Thank you — your form was sent.',
    failed: 'The form could not be sent. Please try again.',
  };

  function messages(el) {
    var raw = (el.getAttribute('data-studio-messages') || '').split('|');
    return { required: raw[0] || '', second: raw[1] || '' };
  }

  function fieldBox(control) {
    var box = control.closest('[data-studio-field]');
    return box || control.parentElement;
  }

  function setError(box, text) {
    var p = box.querySelector('[data-studio-error]');
    if (p) p.textContent = text || '';
    box.classList.toggle('is-invalid', !!text);
    var control = box.querySelector('input, select, textarea');
    if (control) control.setAttribute('aria-invalid', text ? 'true' : 'false');
  }

  /** The error of one control, or '' when it is fine. */
  function check(control) {
    var box = fieldBox(control);
    var own = messages(control.hasAttribute('data-studio-messages') ? control : box);
    var value = (control.value || '').trim();
    var type = control.type;
    if (box.hasAttribute('data-studio-required') || (type === 'radio' && box.hasAttribute('data-studio-required'))) {
      var any = box.querySelector('input[type=radio]:checked, input[type=checkbox]:checked');
      return any ? '' : own.required || DEFAULTS.required;
    }
    if (type === 'checkbox') {
      return control.required && !control.checked ? own.required || DEFAULTS.required : '';
    }
    if (type === 'file') {
      var files = control.files || [];
      if (control.required && !files.length) return own.required || DEFAULTS.required;
      var maxMb = Number(control.getAttribute('data-studio-max-size') || 10);
      for (var i = 0; i < files.length; i++) {
        if (files[i].size > maxMb * 1024 * 1024) return DEFAULTS.fileSize.replace('{n}', maxMb);
        if (control.accept && !accepts(control.accept, files[i])) return DEFAULTS.fileType;
      }
      return '';
    }
    if (control.required && !value) return own.required || DEFAULTS.required;
    if (!value) return '';
    if (type === 'date') {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return DEFAULTS.date;
      if (control.min && value < control.min) return own.second || DEFAULTS.minDate.replace('{d}', control.min);
      if (control.max && value > control.max) return own.second || DEFAULTS.maxDate.replace('{d}', control.max);
      return '';
    }
    if (type === 'email' && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) return own.second || DEFAULTS.email;
    if (type === 'number') {
      var n = Number(value);
      if (isNaN(n)) return DEFAULTS.number;
      if (control.min !== '' && n < Number(control.min)) return own.second || DEFAULTS.min.replace('{n}', control.min);
      if (control.max !== '' && n > Number(control.max)) return own.second || DEFAULTS.max.replace('{n}', control.max);
    }
    var minLength = control.getAttribute('minlength');
    var maxLength = control.getAttribute('maxlength');
    if (minLength && value.length < Number(minLength)) return DEFAULTS.minLength.replace('{n}', minLength);
    if (maxLength && value.length > Number(maxLength)) return DEFAULTS.maxLength.replace('{n}', maxLength);
    var pattern = control.getAttribute('pattern');
    if (pattern) {
      try {
        if (!new RegExp('^(?:' + pattern + ')$').test(value)) return own.second || DEFAULTS.pattern;
      } catch (e) {
        /* a broken pattern never blocks the visitor */
      }
    }
    return '';
  }

  function controls(form, scope) {
    var out = [];
    var seen = {};
    var all = (scope || form).querySelectorAll('input, select, textarea');
    for (var i = 0; i < all.length; i++) {
      var c = all[i];
      if (!c.name || c.name === 'studio:website' || c.type === 'hidden' || c.type === 'submit') continue;
      if (c.disabled || c.closest('[data-studio-off]')) continue;
      if (c.type === 'radio' || (c.type === 'checkbox' && c.closest('[data-studio-group]'))) {
        if (seen[c.name]) continue;
        seen[c.name] = true;
      }
      out.push(c);
    }
    return out;
  }

  function validate(form, report, scope) {
    var ok = true;
    var first = null;
    controls(form, scope).forEach(function (c) {
      var err = check(c);
      setError(fieldBox(c), err);
      if (err) {
        ok = false;
        if (!first) first = c;
      }
    });
    if (!ok && report && first) first.focus();
    return ok;
  }

  /** Does a file match an accept list (".pdf,image/*,application/pdf")? */
  function accepts(accept, file) {
    var name = (file.name || '').toLowerCase();
    var type = (file.type || '').toLowerCase();
    return accept.split(',').some(function (a) {
      a = a.trim().toLowerCase();
      if (!a) return false;
      if (a.charAt(0) === '.') return name.slice(-a.length) === a;
      if (a.slice(-2) === '/*') return type.indexOf(a.slice(0, -1)) === 0;
      return a === type;
    });
  }

  function data(form) {
    var out = {};
    var fd = new FormData(form);
    fd.forEach(function (v, k) {
      if (k === 'studio:website') return;
      var el = form.querySelector('[name="' + k + '"]');
      if (el && el.closest('[data-studio-off]')) return; // hidden by a rule
      if (typeof v !== 'string') return; // files travel in the multipart body, not in the JSON
      if (k in out) out[k] = [].concat(out[k], v);
      else out[k] = v;
    });
    return out;
  }

  /** The body of a submission: JSON, or multipart when the form carries files (the values as fields, the files as parts). */
  function body(form, preview) {
    var files = form.querySelectorAll('input[type=file]');
    var any = false;
    for (var i = 0; i < files.length; i++) {
      if (files[i].files && files[i].files.length && !files[i].closest('[data-studio-off]') && !files[i].disabled) any = true;
    }
    var values = data(form);
    if (!any) {
      return { headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify({ form: form.getAttribute('data-studio-form'), data: values, preview: preview }) };
    }
    var fd = new FormData();
    fd.append('form', form.getAttribute('data-studio-form'));
    if (preview) fd.append('preview', 'true');
    Object.keys(values).forEach(function (k) {
      [].concat(values[k]).forEach(function (v) { fd.append(k, v); });
    });
    for (var j = 0; j < files.length; j++) {
      if (!files[j].files || files[j].closest('[data-studio-off]') || files[j].disabled) continue;
      for (var f = 0; f < files[j].files.length; f++) fd.append(files[j].name, files[j].files[f], files[j].files[f].name);
    }
    return { headers: { Accept: 'application/json' }, body: fd };
  }

  function status(form, text, kind) {
    var p = form.querySelector('[data-studio-status]');
    if (!p) return;
    p.textContent = text || '';
    p.className = 'studio-form__status' + (kind ? ' studio-form__status--' + kind : '');
  }

  function sent(form) {
    var after = form.getAttribute('data-studio-after') || 'message';
    var redirect = form.getAttribute('data-studio-redirect');
    if (after === 'redirect' && redirect && !form.hasAttribute('data-studio-preview')) {
      window.location.href = /\.html$|\?|^https?:/.test(redirect) ? redirect : redirect + '.html';
      return;
    }
    var message = form.getAttribute('data-studio-message') || DEFAULTS.sent;
    var done = document.createElement('div');
    done.className = 'studio-form__done';
    done.setAttribute('role', 'status');
    done.textContent = message + (form.hasAttribute('data-studio-preview') ? ' (preview: nothing was stored)' : '');
    form.parentNode.insertBefore(done, form);
    form.hidden = true;
  }

  function submit(form) {
    if (!validate(form, true)) return;
    var trap = form.querySelector('input[name="studio:website"]');
    if (trap && trap.value) return; // a bot filled the hidden field
    var action = form.getAttribute('action');
    if (!action || action === '#') {
      // no submit endpoint: the form only shows its after-submit behaviour
      sent(form);
      return;
    }
    status(form, 'Sending…', 'busy');
    var button = form.querySelector('[type=submit]');
    if (button) button.disabled = true;
    var preview = form.hasAttribute('data-studio-preview');
    var req = body(form, preview);
    fetch(action, {
      method: 'POST',
      credentials: 'same-origin',
      headers: req.headers,
      body: req.body,
    })
      .then(function (r) {
        return r.json().then(
          function (j) { return { status: r.status, body: j }; },
          function () { return { status: r.status, body: null }; }
        );
      })
      .then(function (res) {
        if (res.status === 422 && res.body && res.body.errors) {
          // the server saw something the browser let through: show it on the fields
          Object.keys(res.body.errors).forEach(function (name) {
            var box = form.querySelector('[data-studio-field="' + name + '"]');
            if (box) setError(box, res.body.errors[name]);
          });
          status(form, '');
          if (button) button.disabled = false;
          return;
        }
        if (res.status !== 200 || !res.body || !res.body.ok) throw new Error('HTTP ' + res.status);
        status(form, '');
        sent(form);
      })
      .catch(function () {
        status(form, DEFAULTS.failed, 'error');
        if (button) button.disabled = false;
      });
  }

  // ------------------------------------------------------------------ rules

  /** The value of a field by name, as the rules see it: a radio group's choice, a checkbox's value when ticked. */
  function valueOf(form, name) {
    var all = form.querySelectorAll('[name="' + name + '"]');
    if (!all.length) return '';
    var c = all[0];
    if (c.type === 'radio') {
      for (var i = 0; i < all.length; i++) if (all[i].checked) return all[i].value;
      return '';
    }
    if (c.type === 'checkbox') {
      if (all.length > 1 || c.closest('[data-studio-group]')) {
        var picked = [];
        for (var j = 0; j < all.length; j++) if (all[j].checked) picked.push(all[j].value);
        return picked.join(', ');
      }
      return c.checked ? c.value : '';
    }
    return (c.value || '').trim();
  }

  function holds(form, rule) {
    var v = valueOf(form, rule.field);
    var want = rule.value == null ? '' : String(rule.value);
    switch (rule.op) {
      case 'is': return v === want;
      case 'isnot': return v !== want;
      case 'contains': return v.toLowerCase().indexOf(want.toLowerCase()) >= 0;
      case 'empty': return v === '';
      case 'notempty': return v !== '';
      case 'gt': return v !== '' && Number(v) > Number(want);
      case 'lt': return v !== '' && Number(v) < Number(want);
      default: return false;
    }
  }

  function rulesOf(box) {
    // the rules of this box only: not those of a field inside a panel (the editor may wrap the rules
    // element in a decoration div, so "nearest box above" is the test, not "direct child")
    var items = box.querySelectorAll('[data-studio-rules] > [data-studio-rule]');
    if (!items.length) return null;
    var out = [];
    for (var i = 0; i < items.length; i++) {
      if (items[i].parentNode.closest('[data-studio-field]') !== box) continue;
      var d = items[i].dataset;
      if (d.field) out.push({ action: d.action || 'show', field: d.field, op: d.op || 'is', value: d.value || '' });
    }
    return out.length ? out : null;
  }

  /** Applies every rule of the form to its fields: show / hide / require / disable, from the current values. */
  function applyRules(form) {
    // in the editor every field stays on screen (dimmed when a rule would hide it), so it can be edited
    var editing = form.hasAttribute('data-studio-editing');
    var boxes = form.querySelectorAll('[data-studio-field]');
    for (var i = 0; i < boxes.length; i++) {
      var box = boxes[i];
      var rules = rulesOf(box);
      if (!rules) continue;
      var by = { show: [], hide: [], require: [], disable: [] };
      rules.forEach(function (r) { if (by[r.action]) by[r.action].push(holds(form, r)); });
      var all = function (list) { return list.every(function (b) { return b; }); };
      var any = function (list) { return list.some(function (b) { return b; }); };
      var visible = true;
      if (by.show.length) visible = all(by.show);
      if (by.hide.length && any(by.hide)) visible = false;
      var wrap = box.closest('.aem-GridColumn') || box;
      if (editing) {
        box.classList.toggle('studio-form__field--ruled-off', !visible);
        continue;
      }
      wrap.hidden = !visible;
      if (visible) box.removeAttribute('data-studio-off'); else box.setAttribute('data-studio-off', 'true');
      var controls = box.querySelectorAll('input, select, textarea');
      var disabled = by.disable.length ? any(by.disable) : false;
      for (var j = 0; j < controls.length; j++) {
        var c = controls[j];
        if (by.disable.length || c.hasAttribute('data-studio-disabled-by-rule')) {
          c.disabled = disabled;
          if (disabled) c.setAttribute('data-studio-disabled-by-rule', 'true'); else c.removeAttribute('data-studio-disabled-by-rule');
        }
        if (by.require.length) {
          var req = all(by.require);
          if (c.type === 'radio' || (c.type === 'checkbox' && box.hasAttribute('data-studio-group'))) {
            if (req) box.setAttribute('data-studio-required', 'true'); else box.removeAttribute('data-studio-required');
          } else c.required = req;
          var star = box.querySelector('.studio-form__required');
          if (star) star.hidden = !req;
        }
      }
      if (!visible) setError(box, '');
    }
  }

  // ------------------------------------------------------------------ steps

  /** The top-level panels of a form with steps: one at a time, Next / Back, a progress line. */
  function steps(form) {
    if (!form.hasAttribute('data-studio-steps') || form.hasAttribute('data-studio-editing')) return null;
    var panels = [];
    var all = form.querySelectorAll('.studio-form__panel');
    for (var i = 0; i < all.length; i++) {
      var p = all[i];
      // only panels directly in the form's own grid (not nested in another panel)
      if (p.parentElement && p.parentElement.closest && p.parentElement.closest('.studio-form__panel')) continue;
      panels.push(p);
    }
    if (panels.length < 2) return null;
    var state = form.studioSteps || (form.studioSteps = { index: 0 });
    var submitRow = form.querySelector('.studio-form__actions');
    var nav = form.querySelector('.studio-form__steps');
    if (!nav) {
      nav = document.createElement('div');
      nav.className = 'studio-form__steps';
      nav.innerHTML =
        '<div class="studio-form__progress" role="list"></div>' +
        '<div class="studio-form__stepnav">' +
        '<button type="button" class="studio-form__back" data-studio-back>Back</button>' +
        '<button type="button" class="studio-form__next" data-studio-next>Next</button>' +
        '</div>';
      var status = form.querySelector('[data-studio-status]');
      if (status && status.parentNode === form) form.insertBefore(nav, status);
      else form.appendChild(nav);
      nav.querySelector('[data-studio-back]').addEventListener('click', function () { go(form, -1); });
      nav.querySelector('[data-studio-next]').addEventListener('click', function () { go(form, 1); });
    }
    var shown = panels.filter(function (p) { return !p.hasAttribute('data-studio-off'); });
    if (state.index >= shown.length) state.index = shown.length - 1;
    var progress = nav.querySelector('.studio-form__progress');
    progress.innerHTML = '';
    shown.forEach(function (p, i) {
      var dot = document.createElement('span');
      dot.className = 'studio-form__step' + (i === state.index ? ' is-current' : i < state.index ? ' is-done' : '');
      dot.setAttribute('role', 'listitem');
      dot.textContent = p.getAttribute('data-studio-title') || 'Step ' + (i + 1);
      progress.appendChild(dot);
    });
    panels.forEach(function (p) {
      var wrap = p.closest('.aem-GridColumn') || p;
      var on = shown.indexOf(p) === state.index;
      wrap.hidden = !on || p.hasAttribute('data-studio-off');
    });
    var last = state.index === shown.length - 1;
    nav.querySelector('[data-studio-back]').hidden = state.index === 0;
    nav.querySelector('[data-studio-next]').hidden = last;
    if (submitRow) {
      // the submit row joins the step nav, so Back and the submit button share a line on the last step
      var col = submitRow.closest('.aem-GridColumn');
      if (col && col !== submitRow) col.hidden = true;
      var stepnav = nav.querySelector('.studio-form__stepnav');
      if (submitRow.parentNode !== stepnav) stepnav.appendChild(submitRow);
      submitRow.hidden = !last;
    }
    return { panels: shown, state: state };
  }

  function go(form, delta) {
    var s = steps(form);
    if (!s) return;
    if (delta > 0 && !validate(form, true, s.panels[s.state.index])) return;
    s.state.index = Math.max(0, Math.min(s.panels.length - 1, s.state.index + delta));
    steps(form);
    form.scrollIntoView({ block: 'start', behavior: 'smooth' });
  }

  function refresh(form) {
    applyRules(form);
    steps(form);
  }

  function wire(form) {
    if (form.getAttribute('data-studio-wired')) return;
    form.setAttribute('data-studio-wired', 'true');
    // a hidden field without a value carries the address of the page the form is on
    var pages = form.querySelectorAll('input[type=hidden][data-studio-page]');
    for (var p = 0; p < pages.length; p++) pages[p].value = window.location.href.split('?')[0];
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      refresh(form);
      submit(form);
    });
    refresh(form);
    // check a field once the visitor leaves it, then on every change
    form.addEventListener(
      'blur',
      function (e) {
        var c = e.target;
        if (c && c.name && /^(INPUT|SELECT|TEXTAREA)$/.test(c.tagName)) {
          setError(fieldBox(c), check(c));
          c.setAttribute('data-studio-touched', 'true');
        }
      },
      true
    );
    form.addEventListener('input', function (e) {
      var c = e.target;
      if (c && c.getAttribute && c.getAttribute('data-studio-touched')) setError(fieldBox(c), check(c));
      refresh(form);
    });
    form.addEventListener('change', function (e) {
      var c = e.target;
      if (c && c.name) setError(fieldBox(c), check(c));
      refresh(form);
    });
  }

  function init(root) {
    var forms = (root || document).querySelectorAll('form.studio-form');
    for (var i = 0; i < forms.length; i++) wire(forms[i]);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', function () { init(); });
  else init();

  // the editor re-renders parts of the page: wire forms that appear later
  if (window.MutationObserver) {
    var observer = new MutationObserver(function (records) {
      // our own changes (the steps' progress line) come back as records: ignore those
      var ours = records.every(function (r) { return r.target.closest && r.target.closest('.studio-form__steps'); });
      if (ours) return;
      init();
      var forms = document.querySelectorAll('form.studio-form[data-studio-wired]');
      for (var i = 0; i < forms.length; i++) refresh(forms[i]);
      observer.takeRecords();
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });
  }

  window.StudioForms = { init: init, validate: validate, data: data, applyRules: applyRules, holds: holds };
})();
