---
title: A tour of the interface
description: Tabs, the sidebar, the inspector, right-click menus and the command palette of Sensible Studio.
order: 20
---

Sensible Studio is one window that never reloads. Everything you open is a place with an address: a folder of pages, a file, a template, even a properties form. You move between places the way you do in a browser or a file manager: tabs, Back and Forward, a sidebar, and one field that takes you anywhere.

## Home

Home is where a new tab starts. It has three parts:

- **Content**: Pages, Experience Fragments, Content Fragments, Media, Generate Variations, Forms and Projects.
- **Structure**: Templates, Content Fragment Models and Tags.
- **System**: the tools around the content, in groups: Flows, Publishing, Translations, Forms, Audiences, Media, Users, Developer and Web Console.

The house icon in the top bar brings you back to Home from anywhere.

## Tabs

The bar at the top holds your tabs. Each tab has its own history, so Back and Forward retrace what you did in that tab and nothing else.

| To do this | Do this |
|---|---|
| Open a new tab | `+` in the tab bar, or `Alt+T` |
| Open a link in a new tab | `Cmd`-click it (`Ctrl`-click on Windows and Linux) |
| Close the tab | `Alt+W` |
| Reopen the tab you closed | `Alt+Shift+T` |
| Go back, go forward | the arrows left of the tabs, or `Alt+←` and `Alt+→` |

Your tabs, the place each one is at, and the widths you set are remembered in the browser. Close the window, open it tomorrow, and it is as you left it.

## Two places side by side

A tab can show two places next to each other. Press `Alt+\`, or right-click the tab and choose **Split View**. Each side has its own history; a thin line at the top marks the side that has the focus, and the Back and Forward buttons act on that side.

Split view is how you drag between places. Open Media on one side and a page in the editor on the other, then drag an image onto the page.

Press `Alt+\` again, or choose **Close Split**, to keep only the side with the focus.

## The sidebar

`Alt+B` shows and hides the sidebar at the left. It has three lists:

- **Recent**: the places you were at lately, newest first.
- **Pinned**: the places you chose to keep at hand.
- **Inbox**: the flow steps and project tasks that are waiting for you. The bell in the top bar shows how many there are.

## Go anywhere: the command palette

Press `Cmd+K` (`Ctrl+K`), or click the magnifier in the top bar, and start typing. The palette finds:

- your open tabs;
- every console and tool, by name;
- the places you were at recently;
- pages, media and content fragments, by title or name;
- the actions for what you have selected, such as Publish or Move.

`Enter` opens the result in the current tab. `Cmd+Enter` opens it in a new one. `Esc` closes the palette.

## Select, then act

In every console, the same three things work on a selection:

- **The action bar** appears above the list when you select something.
- **Right-click** an item for the same actions as a menu.
- **The command palette** lists them under *Selection*.

A few actions have a key of their own in the Pages console: `e` edits, `p` opens the properties, `m` moves, and `Backspace` deletes.

## The inspector

In Pages, Experience Fragments and Forms, the panel at the right tells you about what you selected without leaving the list. Open and close it with the **Inspector** button in the bar, or with `Alt` and the backtick key. It has four tabs:

| Tab | Shortcut | Shows |
|---|---|---|
| Properties | `Alt+3` | Title, template, who changed the page and when, its versions, and whether it follows a source |
| Timeline | `Alt+4` | Everything that happened to the page: changes, versions, flows, comments, annotations, releases |
| References | `Alt+5` | The pages that link to it, its linked copies and the releases that contain it |
| Site | `Alt+6` | A summary of the site the page belongs to |

With nothing selected, the inspector describes the folder you are in.

## The left rail

The selector at the left of the bar opens a rail beside the list:

- **Content Tree** (`Alt+1`): the tree of pages, opened down to where you are.
- **Filter** (`Alt+2`): search the pages below a path by text, template, tags or date, and save searches you use often.

The backtick key hides the rail.

## Next

Now [create your first page](first-page.md), or read the [author guide](../author/index.md) from the start.
