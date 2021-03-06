$(document).ready(function() {
  var ace = new Ace2Editor();
  ace.init("editorcontainer", "", function() {
    $("#editorloadingbox").hide();
    ace.focus();
    
    if (clientVars.scrollToLineNo) {
      ace.scrollToLineNo(clientVars.scrollToLineNo);
    }
  });
  ace.setProperty('stylenamespace', clientVars.userId);
  
  Layout.onResize = ace.adjustSize;
  
  var user = {
    userId: clientVars.userId,
    name: clientVars.userName,
    colorId: clientVars.colorId
    // ip, userAgent
  };
  var options = {
    colorPalette: clientVars.colorPalette
  };
  
  var collab = getCollabClient(ace,
                               clientVars.collab_client_vars,
                               user,
                               options);
  
  var userlist = new UserList(ace, collab, user, options);
  var testor = new Testor(collab);
  
  var orgImportsWidget = makeOrgImportsWidget($, ace, function(selection) {
    collab.sendExtendedMessage({ type: "ORGIMPORTS_RESOLVED" , choices: selection });
  });
  
  var outsourceWidget = makeOutsourceWidget(userlist, function(request) {
    collab.sendExtendedMessage({ type: "OUTSOURCE_REQUEST", action: "create", request: request });
  }, options);
  
  collab.setOnInternalAction(function(action) {
    if (action == "commitPerformed") {
      $("#syncstatussyncing").css('display', 'block');
      //$("#syncstatusdone").css('display', 'none');
    } else if (action == "newlyIdle") {
      $("#syncstatussyncing").fadeOut(1000);
      //$("#syncstatussyncing").css('display', 'none');
      //$("#syncstatusdone").css('display', 'block').fadeOut(1000);
    }
  });
  collab.setOnChannelStateChange(function(state, info) {
    if (state == "CONNECTED") {
      $("#connstatusconnecting").css('display', 'none');
      $("#connstatusdisconnected").css('display', 'none');
      setTimeout(function() {
        collab.sendExtendedMessage({ type: "ANNOTATIONS_REQUEST" });
        collab.sendExtendedMessage({ type: "TESTS_REQUEST", action: "state" });
        collab.sendExtendedMessage({ type: "OUTSOURCE_REQUEST", action: "state" });
      }, 0);
    } else if (state == "DISCONNECTED") {
      $("#connstatusconnecting").css('display', 'none');
      $("#connstatusdisconnected").css('display', 'block');
    } else {
      $("#connstatusconnecting").css('display', 'block');
    }
  });
  
  collab.setOnExtendedMessage("APPLY_CHANGESET_AS_USER", function(msg) {
    ace.applyChangesetAsUser(msg.changeset);
  });
  collab.setOnExtendedMessage("CODECOMPLETE_PROPOSALS", function(msg) {
    ace.showCodeCompletionProposals(msg.offset, msg.proposals);
  });
  collab.setOnExtendedMessage("ANNOTATIONS", function(msg) {
    if (clientVars.userId == msg.userId) {
      ace.setAnnotations(msg.annotationType, msg.annotations);
    }
  });
  collab.setOnExtendedMessage("TEST_RESULT", function(msg) {
    testor.updateTest(msg.test, msg.result);
  });
  collab.setOnExtendedMessage("TEST_ORDER", function(msg) {
    testor.updateOrder(msg.order);
  });
  collab.setOnExtendedMessage("ORGIMPORTS_PROMPT", function(msg) {
    orgImportsWidget.handleOrgImportsResolve(msg.suggestion);
  });
  collab.setOnExtendedMessage("OUTSOURCED", function(msg) {
    outsourceWidget.updateRequests(msg.requests);
  });
  
  ace.addKeyHandler(function(event, char, cb, cmdKey) {
    if (( ! cb.specialHandled) && cmdKey && char == "s" &&
        (event.metaKey || event.ctrlKey)) {
      // cmd-S ("sync")
      event.preventDefault();
      if (collab.getChannelState() != "CONNECTED") {
        $("#syncstatuswarning").css('display', 'block');
        $("#syncstatuswarning").delay(2000).fadeOut(1000);
      }
      cb.specialHandled = true; 
    }
  });
  
  ace.addKeyHandler(function(event, char, cb, cmdKey) {
    if (( ! cb.specialHandled) && cmdKey && char == "f" &&
        (event.metaKey || event.ctrlKey) && event.shiftKey) {
      // shift-cmd-F (code formatting)
      event.preventDefault();
      collab.sendExtendedMessage({ type: "FORMAT_REQUEST" });
      cb.specialHandled = true;
    }
  });
  
  ace.addKeyHandler(function(event, char, cb, cmdKey) {
    if (( ! cb.specialHandled) && cmdKey && char == "o" &&
        (event.metaKey || event.ctrlKey) && event.shiftKey) {
      // shift-cmd-o (code formatting)
      event.preventDefault();
      collab.sendExtendedMessage({ type: "ORGIMPORTS_REQUEST" });
      cb.specialHandled = true;
    }
  });

  $("#format").click(function() {
    collab.sendExtendedMessage({ type: "FORMAT_REQUEST" });
    return false;
  });
  $("#orgimports").click(function() {
    collab.sendExtendedMessage({ type: "ORGIMPORTS_REQUEST" });
    return false;
  });
  $("#runtests").click(function() {
    collab.sendExtendedMessage({ type: "TESTS_RUN_REQUEST" });
    return false;
  });
  $("#outsource").click(function() {
    outsourceWidget.createRequest(ace.getSelection());
    return false;
  });
  $("#forcecommit").click(function() {
    var selection = ace.getSelection();
    collab.sendExtendedMessage({ type: "FORCE_COMMIT", start: selection.startOffset, end: selection.endOffset });
    return false;
  });
});
