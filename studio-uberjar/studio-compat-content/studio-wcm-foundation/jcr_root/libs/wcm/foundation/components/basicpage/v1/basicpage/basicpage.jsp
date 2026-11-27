<%@page session="false" %><%--
  A page of this type holds things (the forms of a site, say) and is no page to look at: asked for as one, it is
  not found. Without this script the answer was 200 with nothing in it.
--%><% response.sendError(404); %>