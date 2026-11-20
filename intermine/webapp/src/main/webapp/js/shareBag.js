(function($) {

    var editingUser = false;

    this.startSharingBag = function(id) {
      if (editingUser) {
        window.stopSharingBag();
      }
      editingUser = id;
      window.hideEl('addLink-' + id);
      window.showEl('shareEdit-' + id);
    };

    this.stopSharingBag = function() {
      if (editingUser) {
        window.hideEl('shareEdit-' + editingUser);
        window.showEl('addLink-' + editingUser);
      }
      editingUser = false;
    };

    this.addUser = function(id, bagName) {
      var user = $('#userValue-' + id).val();
      var callBack = function(returnStr) {
        var notification, failureMessage = $('<span>').text(returnStr);
        if (returnStr == 'ok') {
          window.refreshSharingUsers(bagName, id);
          $('#userValue-' + id).val('');
        } else {
            if (returnStr.match(/not found/)) {
                failureMessage.text(returnStr + " Would you like to send an invitation to " + user + "? ");
                $("<button>OK</button>").appendTo(failureMessage).click(function() {
                    notification.close();
                    if (window.$SERVICE) {
                      var errHandler = function(err) {
                        new window.FailureNotification({message: $('<span>').text(String(err))}).render();
                      };
                      window.$SERVICE.fetchList(bagName, function(l) {
                          l.inviteUserToShare(user).fail(errHandler);
                      }).fail(errHandler);
                    } else {
                        new window.FailureNotification({message: "You are not logged in"}).render();
                    }
                });
            }
            notification = new window.FailureNotification({message: failureMessage});
            notification.render();
        }
      };
      if (user != '') {
        window.AjaxServices.addUserToShareBag(user, bagName, callBack);
      }
    };

    this.refreshSharingUsers = function(bagName, id) {
      var callBack = function(users) {
        window.displayUsers(id, users, bagName);
      };
      window.AjaxServices.getUsersSharingBag(bagName, callBack);
    };

    this.deleteUser = function(user, bagName, id) {
      var callBack = function(returnStr) {
        if (returnStr == 'ok') {
          window.refreshSharingUsers(bagName, id);
        } else {
          new window.FailureNotification({message: $('<span>').text(returnStr)}).render();
        }
      };

      window.AjaxServices.deleteUserToShareBag(user, bagName, callBack);
    };

    this.displayUsers = function(id, users, bagName) {
      $('#sharingUsers-' + id).empty();
      for (var i = 0; i < users.length; i++) {
        window.addUserSpan(id, users[i], bagName);
      }
    };

    this.addUserSpan = function(id, user, bagName) {
      var parent = $('#sharingUsers-' + id);
      var span = $('<span class="tag">');
      var a = $('<a class="deleteTagLink">[x]</a>');
      a.click(function() {
          window.deleteUser(user, bagName, id);
      });
      span.text(user).append(a);
      parent.append(span);
    };

    $(document).on('click', '.share-bag .deleteTagLink[data-user]', function() {
      var container = $(this).closest('.share-bag');
      window.deleteUser($(this).attr('data-user'), container.attr('data-bag-name'),
          container.attr('data-share-id'));
    });
    $(document).on('click', '.share-bag .start-sharing', function() {
      window.startSharingBag($(this).closest('.share-bag').attr('data-share-id'));
    });
    $(document).on('click', '.share-bag .add-sharing-user', function() {
      var container = $(this).closest('.share-bag');
      window.addUser(container.attr('data-share-id'), container.attr('data-bag-name'));
    });
    $(document).on('click', '.share-bag .stop-sharing', function() {
      window.stopSharingBag();
    });

}).call(window, window.jQuery);
