/*
 * Responsive Image: turning a spin set by dragging, and moving and zooming the enlarged picture.
 * One script for every image on the page: it listens on the document, so any number of
 * components (and ones added later) work, and loading it twice does nothing more.
 * Without it everything still works by keyboard and by clicks (the pictures are radio buttons
 * and the full size is a checkbox); this only adds the pointer gestures.
 */
(function () {
    "use strict";
    if (window.studioDynamicMediaViewer) {
        return;
    }
    window.studioDynamicMediaViewer = true;

    var MAX_ZOOM = 2; // times the picture's own size

    // --- Spin: the pointer's way across the picture goes through its pictures, round and round.
    var spin = null;

    function picks(set) {
        return set.querySelectorAll(".studio-dynamicmedia__pick");
    }

    function current(p) {
        for (var i = 0; i < p.length; i++) {
            if (p[i].checked) {
                return i;
            }
        }
        return 0;
    }

    // --- The enlarged picture: drag to move it when it is larger than the window, pinch (or
    // Ctrl + wheel, as trackpads send a pinch) to zoom, + / - / 0 on the keyboard.
    var pan = null;
    var touches = {};
    var pinch = null;
    var dragged = false;

    function largeOf(el) {
        return el && el.closest ? el.closest(".studio-dynamicmedia__large") : null;
    }

    function parts(large) {
        return {
            box: large,
            check: large.querySelector(".studio-dynamicmedia__actual"),
            img: large.querySelector("img")
        };
    }

    function fitWidth(p) {
        var w = p.img.naturalWidth || p.img.width;
        var h = p.img.naturalHeight || p.img.height;
        if (!w || !h) {
            return 0;
        }
        var maxW = window.innerWidth * 0.96;
        var maxH = window.innerHeight * 0.96;
        return Math.min(w, maxW, maxH * w / h);
    }

    // Shows the picture `width` pixels wide, keeping the point under (cx, cy) where it is.
    function zoomTo(p, width, cx, cy) {
        var fit = fitWidth(p);
        var natural = p.img.naturalWidth || fit;
        if (!fit) {
            return;
        }
        width = Math.max(fit, Math.min(natural * MAX_ZOOM, width));
        var before = p.img.getBoundingClientRect();
        var rx = before.width ? (cx - before.left) / before.width : 0.5;
        var ry = before.height ? (cy - before.top) / before.height : 0.5;
        if (width <= fit + 1) {
            p.check.checked = false;
            p.img.style.width = "";
            return;
        }
        p.check.checked = true;
        p.img.style.width = Math.round(width) + "px";
        var after = p.img.getBoundingClientRect();
        p.box.scrollLeft += (after.left + rx * after.width) - cx;
        p.box.scrollTop += (after.top + ry * after.height) - cy;
    }

    function zoomBy(p, factor, cx, cy) {
        zoomTo(p, p.img.getBoundingClientRect().width * factor, cx, cy);
    }

    function middle(p) {
        var r = p.box.getBoundingClientRect();
        return [r.left + r.width / 2, r.top + r.height / 2];
    }

    document.addEventListener("pointerdown", function (e) {
        var set = e.target.closest && e.target.closest(".studio-dynamicmedia__set[data-spin]");
        if (set && e.button <= 0) {
            var p = picks(set);
            if (p.length > 1) {
                spin = { set: set, picks: p, x0: e.clientX, i0: current(p) };
                try {
                    set.setPointerCapture(e.pointerId);
                } catch (x) { /* not capturable */ }
                e.preventDefault();
            }
            return;
        }
        var large = largeOf(e.target);
        if (!large || e.button > 0) {
            return;
        }
        var lp = parts(large);
        if (!lp.img) {
            return;
        }
        dragged = false;
        if (e.pointerType === "touch") {
            touches[e.pointerId] = [e.clientX, e.clientY];
            var ids = Object.keys(touches);
            if (ids.length === 2) {
                var a = touches[ids[0]];
                var b = touches[ids[1]];
                pinch = { p: lp, d0: Math.hypot(a[0] - b[0], a[1] - b[1]) || 1, w0: lp.img.getBoundingClientRect().width };
                dragged = true;
            }
            return; // one finger: the browser scrolls the picture itself
        }
        pan = { p: lp, x0: e.clientX, y0: e.clientY, l0: large.scrollLeft, t0: large.scrollTop };
    });

    document.addEventListener("pointermove", function (e) {
        if (spin) {
            var n = spin.picks.length;
            var step = Math.max(6, spin.set.clientWidth / n / 1.5);
            var k = ((spin.i0 + Math.round((spin.x0 - e.clientX) / step)) % n + n) % n;
            if (!spin.picks[k].checked) {
                spin.picks[k].checked = true;
            }
            return;
        }
        if (pinch && touches[e.pointerId]) {
            touches[e.pointerId] = [e.clientX, e.clientY];
            var ids = Object.keys(touches);
            if (ids.length === 2) {
                var a = touches[ids[0]];
                var b = touches[ids[1]];
                var d = Math.hypot(a[0] - b[0], a[1] - b[1]);
                zoomTo(pinch.p, pinch.w0 * d / pinch.d0, (a[0] + b[0]) / 2, (a[1] + b[1]) / 2);
                e.preventDefault();
            }
            return;
        }
        if (pan) {
            var dx = e.clientX - pan.x0;
            var dy = e.clientY - pan.y0;
            if (!dragged && Math.abs(dx) + Math.abs(dy) < 5) {
                return;
            }
            if (!pan.p.check.checked) {
                return; // a fitted picture has nowhere to go
            }
            dragged = true;
            pan.p.box.scrollLeft = pan.l0 - dx;
            pan.p.box.scrollTop = pan.t0 - dy;
            e.preventDefault();
        }
    });

    function up(e) {
        spin = null;
        pan = null;
        delete touches[e.pointerId];
        if (Object.keys(touches).length < 2) {
            pinch = null;
        }
    }
    document.addEventListener("pointerup", up);
    document.addEventListener("pointercancel", up);

    // A drag or a pinch is not a click: the picture keeps its size.
    document.addEventListener("click", function (e) {
        var large = largeOf(e.target);
        if (large && dragged) {
            dragged = false;
            e.preventDefault();
            e.stopPropagation();
            return;
        }
        // a click switches between fitted and full size: full size means the picture's own size
        if (large && e.target.tagName === "IMG") {
            e.target.style.width = "";
        }
    }, true);

    document.addEventListener("wheel", function (e) {
        var large = largeOf(e.target);
        if (!large || !e.ctrlKey) {
            return;
        }
        e.preventDefault();
        zoomBy(parts(large), Math.exp(-e.deltaY / 200), e.clientX, e.clientY);
    }, { passive: false });

    document.addEventListener("keydown", function (e) {
        var large = largeOf(e.target);
        if (!large || e.ctrlKey || e.metaKey || e.altKey) {
            return;
        }
        var p = parts(large);
        var m = middle(p);
        if (e.key === "+" || e.key === "=") {
            zoomBy(p, 1.25, m[0], m[1]);
        } else if (e.key === "-") {
            zoomBy(p, 0.8, m[0], m[1]);
        } else if (e.key === "0") {
            zoomTo(p, 0, m[0], m[1]);
        } else {
            return;
        }
        e.preventDefault();
    });
})();
