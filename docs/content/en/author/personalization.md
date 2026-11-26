---
title: Personalization
description: Show a different experience to each audience in Sensible Studio, measure which one reaches its goal, and test pages as a particular visitor.
order: 160
---

Personalization shows different content to different visitors: a winter offer to someone in the northern hemisphere in January, a shorter page to someone on a phone. It is made of four things that build on each other:

1. An **audience** is a rule about the visitor.
2. A **personalized component** has one **experience** per audience, and a default for everyone else.
3. A **goal** says what counts as success, so results can be counted.
4. A **report** shows which experience does best.

Nothing about a visitor is stored on the server. What the site knows about a visitor stays in that visitor's browser; only counts are sent back.

## Audiences

An audience answers one question about the visitor: does this person match, yes or no?

Create audiences under **Home › Audiences › Audiences**, or from the **Personalize** panel while you are on a page. Give the audience a title, then build its rule from conditions:

| What you can ask about | Examples |
|---|---|
| **The profile** | Age, gender, interests, loyalty level. Your site fills these in, for example after sign-in |
| **The place** | Country, city, hemisphere |
| **The device** | Phone, tablet or desktop; browser; operating system |
| **The moment** | Month, weekday, hour |
| **What they read** | The tags of the pages they visited most |

Combine conditions with **and** and **or**. An audience with no condition matches nobody, so a half-finished audience cannot take over a page.

When a visitor matches several audiences that a component has experiences for, the audience with the highest **boost** wins.

## Personalize a component

1. Open the page in the editor and switch to **Personalize** mode.
2. Select the component and choose **Target** in its toolbar.
3. Choose **Add Experience**, pick the audience, and give the experience a title. It starts as a copy of what you see.
4. Change it. Each experience is edited like any component.

What the component showed before becomes the **default** experience, for visitors who match no audience.

Click an experience in the panel to see and edit it on the page. **Remove personalization** keeps the experience you are looking at as the component and drops the others. That is also how you make the winner permanent.

### A whole page instead of a component

When the pages for two audiences differ too much to be experiences of one component, send the audience to another page. In the **Personalize** panel, under **Audience redirects**, add a row: this audience goes to that page.

The redirect happens in the visitor's browser, so they may glimpse the first page. For a few components, experiences are smoother.

## Goals

A goal tells Sensible Studio what you are trying to achieve with a personalized component. Set it in the **Personalize** panel, under **Goal & Settings**.

| Goal | Counted when |
|---|---|
| **Clicked** | The visitor clicks in the experience. You can narrow it to one element, such as the button |
| **Viewed page** | The visitor reaches a page you name, later in the same visit |
| **Custom event** | The site's own code reports it: a purchase, a sign-up |
| **No goal** | Only how often each experience was shown is counted |

Each visitor counts once per visit, so reloading a page does not inflate the numbers. Only real visits count: the editor and previews never do.

### Test A against B

To learn which of two versions works better for *everyone*, split the visitors by percentage instead of by audience: half see one experience, half the other. The report then tells you which one reaches the goal more often.

## Reports

In the **Personalize** panel, each experience shows how often it was shown, how often the goal was reached, and the rate. The best one is marked.

**Home › Audiences › Reports** shows the same for the whole site: every personalized component, its goal, its experiences and the winner. From a row you open the page straight in Personalize mode.

Give a result time. A difference of a few visits means nothing; wait until each experience has been shown some hundreds of times.

## See the page as someone else

In the editor's **Preview** mode, the **Visitor simulator** bar lets you become a particular visitor:

- choose a **persona**, a ready-made visitor such as "Weekend Hiker", on a phone, in Romania;
- or set the values yourself: age, country, device, date and time.

The page shows the experiences that visitor would get. Administrators manage personas under **Home › Audiences › Personas**, and what the bar offers under **Home › Audiences › ContextHub**.

## Publishing

Audiences are content, kept in the site's configuration. A personalized page on the live site needs its audiences there too: when you publish the page, the audiences it uses are offered to be published with it.

Personalized pages stay fast. Every visitor gets the same page from the cache, and the choice of experience is made in the browser.
