<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>
<%@ taglib uri="/WEB-INF/struts-html.tld" prefix="html" %>
<%@ taglib uri="/WEB-INF/struts-tiles.tld" prefix="tiles" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="im"%>

<!-- shareBag.jsp -->

<html:xhtml/>
<tiles:importAttribute name="bagName" ignore="true"/>
<tiles:importAttribute name="isBagValid" ignore="true"/>
<tiles:importAttribute name="id" ignore="true"/>
<div>
<span id="shareBag-${id}" class="share-bag" data-share-id="<c:out value="${id}"/>"
      data-bag-name="<c:out value="${bagName}"/>">
  <span id="sharingUsers-${id}" class="current-tags">
    <c:forEach items="${currentSharingUsers}" var="user">
      <span class="tag"><c:out value="${user}"/><a class="deleteTagLink"
          data-user="<c:out value="${user}"/>">[x]</a></span>
    </c:forEach>
  </span>
<c:if test="${shareBags && isBagValid}">
  <span id="addLink-${id}">
    <a class="addTagLink start-sharing">Share with users</a>
  </span>
  <span id="shareEdit-${id}" style="display:none; white-space:nowrap">
      <br/>
    <input type="text" style="border: 1px solid #888; padding: 2px; font-size: 10px" size="18" id="userValue-${id}" name="user"/>
    <input type="button" class="add-sharing-user" style="font-size: 10px" value="Add"/>
    <input type="button" class="stop-sharing" style="font-size: 10px" value="Done"/>
  </span>
</c:if>
</span>

</div>

<!-- /shareBag.jsp -->
