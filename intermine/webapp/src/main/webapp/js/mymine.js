

   window.selectColumnCheckbox = function(form, type) {
           var deleteButton = document.getElementById('delete_button');
           var removeButton = document.getElementById('remove_button');
           var exportButton = document.getElementById('export_button');

       var columnCheckBox = 'selected_' + type;
       var checked = document.getElementById(columnCheckBox).checked;

       if (deleteButton != null) {
           deleteButton.disabled = !checked;
       }
       if (removeButton != null) {
           removeButton.disabled = !checked;
       }
       if (exportButton != null) {
           exportButton.disabled = !checked;
       }
       for(var i=0;i < form.elements.length;i++) {
           var thiselm = form.elements[i];
           var testString = columnCheckBox + '_';
           if(thiselm.id.indexOf(testString) != -1)
               thiselm.checked = checked;
       }
   };


   window.setDeleteDisabledness = function(form, type) {

           var deleteButton = document.getElementById('delete_button');
           var removeButton = document.getElementById('remove_button');
           var exportButton = document.getElementById('export_button');

       var checkBoxPrefix = 'selected_' + type + '_';
       var deleteDisable = true;
       var columnCheckBoxChecked = true;

       for(var i=0;i < form.elements.length;i++) {
           var thiselm = form.elements[i];

           if (thiselm.id.indexOf(checkBoxPrefix) != -1) {
               if (thiselm.checked) {
                   deleteDisable = false;
               } else {
                   columnCheckBoxChecked = false;
               }
           }
       }
       if (deleteButton != null) {
           deleteButton.disabled = deleteDisable;
       }
       if (removeButton != null) {
           removeButton.disabled = deleteDisable;
       }
       if (exportButton != null) {
           exportButton.disabled = deleteDisable;
       }
       document.getElementById('selected_' + type).checked = columnCheckBoxChecked;
       return true;
   };


  window.noenter = function() {
    return !(window.event && window.event.keyCode == 13);
  };

  window.jQuery(function() {
      window.jQuery('#newApiKeyButton').unbind("click").click(function() {
        window.AjaxServices.generateApiKey(window.$CURRENT_USER, function(response) {
            window.jQuery('span.apikey').text(response).removeClass("nokey");
            window.jQuery('#deleteApiKeyButton').show();
        })
    })
    window.jQuery('#deleteApiKeyButton').unbind("click").click(function() {
        window.AjaxServices.deleteApiKey(window.$CURRENT_USER, function(response) {
            if (response === "deleted") {
                window.jQuery('span.apikey').text("Key Deleted").addClass("nokey");
                window.jQuery('#deleteApiKeyButton').hide();
            }
        })
    })
  });

  window.jQuery(function() {
      var $ = window.jQuery, fail = function(xhr, err, desc) {
          var explanation = desc;
          if (desc == "Bad Request") {
              try {
                  explanation = JSON.parse(xhr.responseText).error;
              } catch {
                  explanation = desc;
              }
          }
          new window.FailureNotification({message: $('<span>').text("Could not change preference - " + explanation)}).render();
      };
      $('table.user-preferences').delegate('button', 'click', function(evt) {
          evt.preventDefault();
          evt.stopImmediatePropagation();
          var remove, notify, clear,
              $btn = $(this),
              $row = $btn.closest('tr'),
              $input = $btn.siblings('input'),
              name = $input.attr('name'), value = $input.val();
          remove = function() {
              $row.slideUp('fast', function() {$row.remove()});
          };
          notify = function() {
              new window.Notification({message: "Saved change successfully", autoRemove: true}).render();
          };
          clear = function() {
              $input.val('');
          };
          if ($btn.hasClass('delete')) {
              window.$SERVICE.manageUserPreferences('DELETE', {key: name}).then(remove, fail);
          } else if ($btn.hasClass('update')) {
              window.$SERVICE.manageUserPreferences('POST', [[name, value]]).then(notify, fail);
          } else if ($btn.hasClass('unset')) {
              window.$SERVICE.manageUserPreferences('DELETE', {key: name}).then(clear, fail);
          }
      });
      $('form.editable-user-preference input[type="checkbox"]').change(function(evt) {
          evt.preventDefault();
          evt.stopImmediatePropagation();
          var $box = $(this), newState = !$box.prop('checked'), name = $box.attr('name'),
              method = (newState ? 'POST' : 'DELETE'),
              data = (newState ? [[name, 'true']] : {key: name});
          window.$SERVICE.manageUserPreferences(method, data).fail(fail);
      });
  });
  
  window.jQuery(function() {
      var $ = window.jQuery, toName = function(i, e) {return $(e).attr('name')};
      $('button.add-user-preference').click(function(evt) {
          evt.preventDefault();
          evt.stopImmediatePropagation();
          var $btn = $(this), $frm = $btn.next();
          $frm.slideDown('fast', function() { $btn.attr({disabled: true}); });
      });
      $('form.add-user-preference').submit(function(evt) {
          evt.preventDefault();
          evt.stopImmediatePropagation();
          var reset, fail, $frm = $(this), $btn = $frm.prev(),
              name = $frm.find('.prefname').val(), val = $frm.find('.prefvalue').val(),
              currentNames = $('table.user-preferences tbody tr input').map(toName).get();

          reset = function() {
              $frm.find('input').val('');
              var $tr = $('<tr>'), $form = $('<form class="editable-user-preference">'), $right = $('<td>');
              $('<td>').text(name).appendTo($tr);
              $('<input type="text">').attr({name: name, value: val}).appendTo($form);
              $form.append(" ");
              $('<button class="update">Update</button>').appendTo($form);
              $form.append(" ");
              $('<button class="delete">Delete</button>').appendTo($form);
              $form.appendTo($right);
              $right.appendTo($tr);
              $('table.user-preferences tbody').append($tr); 
          };
          fail = function(msg) { return function() {
              new window.FailureNotification({message: $('<span>').text(msg)}).render();
          }};
          $frm.slideUp();
          $btn.attr({disabled: false});
          if (window._.contains(currentNames, name)) {
              fail(name + " is already set. Please update or delete the current value")();
          } else if (!name || !val) {
              fail("Both a name and a value are required")();
          } else {
              window.$SERVICE.manageUserPreferences('POST', [[name, val]])
                      .then(reset, fail("Could not add preference"));
          }
      });
      $('form.add-user-preference button.cancel').click(function(evt) {
          evt.preventDefault();
          evt.stopImmediatePropagation();
          var $btn = $(this), $frm = $btn.parent(), $inputs = $frm.find('input'), $adder = $frm.prev();
          $inputs.val('');
          $frm.slideUp();
          $adder.attr({disabled: false});
      });
  });

  window.jQuery(function() {
      var selector = window.jQuery('#classNameSelector');
      selector.unbind("change").change(function() {
          var classname = selector.val();
          window.jQuery('div.classLabels').each(function() {
              if (this.id === 'labelsFor' + classname) {
                  window.jQuery(this).show();
              } else {
                  window.jQuery(this).hide();
              }
          });
      });
  });

