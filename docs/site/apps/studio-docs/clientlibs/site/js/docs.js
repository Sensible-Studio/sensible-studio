/* Sensible Studio documentation: what the pages do in the browser. No dependencies.
   - marks the current page in the sidebar and scrolls it into view
   - "On this page" from the page's h2 titles
   - previous / next from the sidebar's order
   - a Copy button on code blocks, anchors on titles, the menu on small screens */
(function () {
    'use strict';

    function ready(fn) {
        if (document.readyState !== 'loading') { fn(); } else { document.addEventListener('DOMContentLoaded', fn); }
    }

    function slug(text) {
        return text.toLowerCase().trim().replace(/[^a-z0-9À-￿]+/g, '-').replace(/^-+|-+$/g, '');
    }

    function samePage(href) {
        try {
            var url = new URL(href, location.href);
            return url.pathname.replace(/\.html$/, '') === location.pathname.replace(/\.html$/, '');
        } catch (e) { return false; }
    }

    ready(function () {
        var frame = document.querySelector('.root > .cmp-container');
        if (!frame) { return; }
        var sidebar = frame.querySelector(':scope > .navigation');
        var main = frame.querySelector(':scope > .container');

        // the current page in the sidebar
        var links = sidebar ? Array.prototype.slice.call(sidebar.querySelectorAll('.cmp-navigation__item-link')) : [];
        var current = links.filter(function (a) { return samePage(a.getAttribute('href')); })[0];
        if (current) {
            current.setAttribute('aria-current', 'page');
            for (var li = current.closest('.cmp-navigation__item'); li; li = li.parentElement && li.parentElement.closest('.cmp-navigation__item')) {
                li.classList.add('cmp-navigation__item--active');
            }
            var top = current.getBoundingClientRect().top;
            if (top > window.innerHeight - 80) { sidebar.scrollTop += top - window.innerHeight / 2; }
        }

        if (main && sidebar) {
            // anchors on the titles, and "On this page"
            var titles = Array.prototype.slice.call(main.querySelectorAll('h2.cmp-title__text, .cmp-text h3'));
            var used = {};
            titles.forEach(function (h) {
                var holder = h.classList.contains('cmp-title__text') ? h.closest('.cmp-title') : h;
                var id = holder.id && !/^title-[0-9a-f]{6,}$/.test(holder.id) ? holder.id : slug(h.textContent);
                while (used[id]) { id += '-'; }
                used[id] = true;
                holder.id = id;
                var a = document.createElement('a');
                a.className = 'docs-anchor';
                a.href = '#' + id;
                a.textContent = '#';
                a.setAttribute('aria-label', 'Link to this section');
                h.appendChild(a);
            });
            var sections = titles.filter(function (h) { return h.tagName === 'H2'; });
            if (sections.length > 1) {
                var toc = document.createElement('nav');
                toc.className = 'docs-toc';
                toc.setAttribute('aria-label', 'On this page');
                var label = document.createElement('p');
                label.className = 'docs-toc__title';
                label.textContent = 'On this page';
                toc.appendChild(label);
                var tocLinks = sections.map(function (h) {
                    var a = document.createElement('a');
                    a.href = '#' + h.closest('.cmp-title').id;
                    a.textContent = h.firstChild ? h.firstChild.textContent : h.textContent;
                    toc.appendChild(a);
                    return a;
                });
                document.body.appendChild(toc);
                var mark = function () {
                    var at = 0;
                    sections.forEach(function (h, i) { if (h.getBoundingClientRect().top < 120) { at = i; } });
                    tocLinks.forEach(function (a, i) { a.classList.toggle('is-current', i === at); });
                };
                window.addEventListener('scroll', mark, { passive: true });
                mark();
            }

            // previous / next, in the sidebar's order
            if (current) {
                var at = links.indexOf(current);
                var pager = document.createElement('nav');
                pager.className = 'docs-pager';
                [[links[at - 1], 'Previous', ''], [links[at + 1], 'Next', 'docs-pager__next']].forEach(function (entry) {
                    if (!entry[0]) { return; }
                    var a = document.createElement('a');
                    a.href = entry[0].getAttribute('href');
                    if (entry[2]) { a.className = entry[2]; }
                    var small = document.createElement('small');
                    small.textContent = entry[1];
                    a.appendChild(small);
                    a.appendChild(document.createTextNode(entry[0].textContent.trim()));
                    pager.appendChild(a);
                });
                var holder = main.querySelector(':scope > .cmp-container') || main;
                if (pager.children.length) { holder.appendChild(pager); }
            }
        }

        // notes and warnings: a quote that starts with a bold word
        Array.prototype.forEach.call(document.querySelectorAll('blockquote'), function (q) {
            var first = q.querySelector('strong, b');
            if (first && /^(warning|caution|important)/i.test(first.textContent)) { q.classList.add('docs-warning'); }
        });

        // copy a code block
        if (navigator.clipboard) {
            Array.prototype.forEach.call(document.querySelectorAll('pre'), function (pre) {
                var b = document.createElement('button');
                b.type = 'button';
                b.className = 'docs-copy';
                b.textContent = 'Copy';
                b.addEventListener('click', function () {
                    var code = pre.querySelector('code') || pre;
                    navigator.clipboard.writeText(code.textContent).then(function () {
                        b.textContent = 'Copied';
                        setTimeout(function () { b.textContent = 'Copy'; }, 1500);
                    });
                });
                pre.appendChild(b);
            });
        }

        // the menu on small screens
        var menu = document.querySelector('[data-docs-menu]');
        if (menu) {
            menu.addEventListener('click', function () {
                var open = document.body.classList.toggle('docs-menu-open');
                menu.setAttribute('aria-expanded', String(open));
            });
            document.addEventListener('click', function (e) {
                if (document.body.classList.contains('docs-menu-open') && !e.target.closest('.navigation') && !e.target.closest('[data-docs-menu]')) {
                    document.body.classList.remove('docs-menu-open');
                    menu.setAttribute('aria-expanded', 'false');
                }
            });
        }
    });
})();
