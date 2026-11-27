<%@page session="false" %><%--
  AEM's context-aware cloud configurations, head part: here Site configurations > Analytics puts a site's scripts into the head.
  AEM pages include this; Sensible Studio does its part elsewhere (see the note), so it renders nothing.
  Without a script here the include fell to Sling's default rendering of a node.
--%>