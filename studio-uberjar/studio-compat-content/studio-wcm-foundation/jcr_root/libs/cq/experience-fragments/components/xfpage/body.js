/*
 * Sensible Studio: path of the resource that holds an experience fragment variation's content (its root
 * container), used by content.html to render the fragment without page chrome.
 */
"use strict";
use(function () {
    return {
        resourcePath: resource.getPath() + "/root"
    };
});
