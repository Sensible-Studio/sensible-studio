import os, sys, textwrap, json
# everything is relative to this file: forms.js and forms.css beside it, jcr_root and META-INF written beside it
HERE=os.path.dirname(os.path.abspath(__file__))
ROOT=sys.argv[1] if len(sys.argv) > 1 else HERE
J='/libs/studio/forms'
def w(path, content):
    p=ROOT+'/jcr_root'+path if path.startswith('/') else ROOT+'/'+path
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p,'w').write(content.lstrip('\n'))

NS='xmlns:sling="http://sling.apache.org/jcr/sling/1.0" xmlns:cq="http://www.day.com/jcr/cq/1.0" xmlns:jcr="http://www.jcp.org/jcr/1.0" xmlns:nt="http://www.jcp.org/jcr/nt/1.0" xmlns:granite="http://www.adobe.com/jcr/granite/1.0"'
X='<?xml version="1.0" encoding="UTF-8"?>\n'

def component(name, title, desc, group='Forms', container=False, supertype=None, extra=''):
    attrs=[f'jcr:primaryType="cq:Component"', f'jcr:title="{title}"', f'jcr:description="{desc}"', f'componentGroup="{group}"']
    if container: attrs.append('cq:isContainer="{Boolean}true"')
    if supertype: attrs.append(f'sling:resourceSuperType="{supertype}"')
    w(f'{J}/components/{name}/.content.xml', X+f'<jcr:root {NS}\n    '+'\n    '.join(attrs)+extra+'/>\n')

def field(name, label, rt='granite/ui/components/coral/foundation/form/textfield', **props):
    a=[f'jcr:primaryType="nt:unstructured"', f'sling:resourceType="{rt}"', f'fieldLabel="{label}"', f'name="./{name}"']
    for k,v in props.items():
        if k=='children': continue
        a.append(f'{k}="{v}"')
    body=props.get('children','')
    tag=name.replace(':','_')
    if body: return f'<{tag}\n    '+'\n    '.join(a)+f'>\n{body}</{tag}>\n'
    return f'<{tag}\n    '+'\n    '.join(a)+'/>\n'

def checkbox(name, text, desc=None):
    d=f'\n    fieldDescription="{desc}"' if desc else ''
    return f'<{name}\n    jcr:primaryType="nt:unstructured"\n    sling:resourceType="granite/ui/components/coral/foundation/form/checkbox"\n    text="{text}"{d}\n    value="{{Boolean}}true"\n    name="./{name}"/>\n'

def tab(id, title, fields):
    inner=''.join('            '+f for f in textwrap.indent(fields,'').splitlines(True))
    return f'''        <{id}
            jcr:primaryType="nt:unstructured"
            jcr:title="{title}"
            sling:resourceType="granite/ui/components/coral/foundation/container"
            margin="{{Boolean}}true">
            <items jcr:primaryType="nt:unstructured">
{textwrap.indent(fields, '                ')}            </items>
        </{id}>
'''

def dialog(name, title, tabs):
    body=''.join(tabs)
    w(f'{J}/components/{name}/_cq_dialog/.content.xml', X+f'''<jcr:root {NS}
    jcr:primaryType="nt:unstructured"
    jcr:title="{title}"
    sling:resourceType="cq/gui/components/authoring/dialog">
    <content
        jcr:primaryType="nt:unstructured"
        sling:resourceType="granite/ui/components/coral/foundation/container">
        <items jcr:primaryType="nt:unstructured">
            <tabs
                jcr:primaryType="nt:unstructured"
                sling:resourceType="granite/ui/components/coral/foundation/tabs"
                maximized="{{Boolean}}true">
                <items jcr:primaryType="nt:unstructured">
{textwrap.indent(body, '        ')}                </items>
            </tabs>
        </items>
    </content>
</jcr:root>
''')

COMMON = lambda kind: (
    field('name', 'Name', fieldDescription='The key of this field in the submission (letters, digits, _ and -). Required.', required='{Boolean}true')
  + field('label', 'Label')
  + field('placeholder', 'Placeholder') if kind in ('textfield','textarea','email','number') else
    field('name', 'Name', fieldDescription='The key of this field in the submission (letters, digits, _ and -). Required.', required='{Boolean}true')
  + field('label', 'Label'))
HELP = field('help', 'Help text', fieldDescription='Shown under the field.')
DEFAULT = field('value', 'Default value')
REQ = checkbox('required', 'Required') + field('requiredMessage', 'Message when empty', fieldDescription='Default: “This field is required.”')

# ------------------------------------------------------------------ fields
FIELD_HTML = {
'textfield': '''
<div class="studio-form__field studio-form__field--text" data-studio-field="${properties.name}">
    <label class="studio-form__label" for="${resource.name}-${properties.name}">${properties.label || 'Text'}<span class="studio-form__required" data-sly-test="${properties.required}" aria-hidden="true"> *</span></label>
    <input class="studio-form__input" id="${resource.name}-${properties.name}" type="text" name="${properties.name}"
           placeholder="${properties.placeholder}" value="${properties.value}"
           minlength="${properties.minLength}" maxlength="${properties.maxLength}" pattern="${properties.pattern}"
           data-sly-attribute.required="${properties.required ? 'required' : ''}"
           data-studio-messages="${properties.requiredMessage}|${properties.patternMessage}"/>
    <p class="studio-form__help" data-sly-test="${properties.help}">${properties.help}</p>
    <p class="studio-form__error" data-studio-error aria-live="polite"></p>
    <sly data-sly-resource="${'rules' @ resourceType='studio/forms/components/rules'}"></sly>
</div>
''',
'textarea': '''
<div class="studio-form__field studio-form__field--textarea" data-studio-field="${properties.name}">
    <label class="studio-form__label" for="${resource.name}-${properties.name}">${properties.label || 'Text'}<span class="studio-form__required" data-sly-test="${properties.required}" aria-hidden="true"> *</span></label>
    <textarea class="studio-form__input studio-form__textarea" id="${resource.name}-${properties.name}" name="${properties.name}"
              placeholder="${properties.placeholder}" rows="${properties.rows || 4}"
              minlength="${properties.minLength}" maxlength="${properties.maxLength}"
              data-sly-attribute.required="${properties.required ? 'required' : ''}"
              data-studio-messages="${properties.requiredMessage}|">${properties.value}</textarea>
    <p class="studio-form__help" data-sly-test="${properties.help}">${properties.help}</p>
    <p class="studio-form__error" data-studio-error aria-live="polite"></p>
    <sly data-sly-resource="${'rules' @ resourceType='studio/forms/components/rules'}"></sly>
</div>
''',
'email': '''
<div class="studio-form__field studio-form__field--email" data-studio-field="${properties.name}">
    <label class="studio-form__label" for="${resource.name}-${properties.name}">${properties.label || 'Email'}<span class="studio-form__required" data-sly-test="${properties.required}" aria-hidden="true"> *</span></label>
    <input class="studio-form__input" id="${resource.name}-${properties.name}" type="email" name="${properties.name}"
           placeholder="${properties.placeholder}" value="${properties.value}" autocomplete="email"
           data-sly-attribute.required="${properties.required ? 'required' : ''}"
           data-studio-messages="${properties.requiredMessage}|${properties.typeMessage}"/>
    <p class="studio-form__help" data-sly-test="${properties.help}">${properties.help}</p>
    <p class="studio-form__error" data-studio-error aria-live="polite"></p>
    <sly data-sly-resource="${'rules' @ resourceType='studio/forms/components/rules'}"></sly>
</div>
''',
'number': '''
<div class="studio-form__field studio-form__field--number" data-studio-field="${properties.name}">
    <label class="studio-form__label" for="${resource.name}-${properties.name}">${properties.label || 'Number'}<span class="studio-form__required" data-sly-test="${properties.required}" aria-hidden="true"> *</span></label>
    <input class="studio-form__input" id="${resource.name}-${properties.name}" type="number" name="${properties.name}"
           placeholder="${properties.placeholder}" value="${properties.value}"
           min="${properties.min}" max="${properties.max}" step="${properties.step || 'any'}"
           data-sly-attribute.required="${properties.required ? 'required' : ''}"
           data-studio-messages="${properties.requiredMessage}|${properties.rangeMessage}"/>
    <p class="studio-form__help" data-sly-test="${properties.help}">${properties.help}</p>
    <p class="studio-form__error" data-studio-error aria-live="polite"></p>
    <sly data-sly-resource="${'rules' @ resourceType='studio/forms/components/rules'}"></sly>
</div>
''',
'date': '''
<div class="studio-form__field studio-form__field--date" data-studio-field="${properties.name}">
    <label class="studio-form__label" for="${resource.name}-${properties.name}">${properties.label || 'Date'}<span class="studio-form__required" data-sly-test="${properties.required}" aria-hidden="true"> *</span></label>
    <input class="studio-form__input" id="${resource.name}-${properties.name}" type="date" name="${properties.name}"
           value="${properties.value}" min="${properties.minDate}" max="${properties.maxDate}"
           data-sly-attribute.required="${properties.required ? 'required' : ''}"
           data-studio-messages="${properties.requiredMessage}|${properties.rangeMessage}"/>
    <p class="studio-form__help" data-sly-test="${properties.help}">${properties.help}</p>
    <p class="studio-form__error" data-studio-error aria-live="polite"></p>
    <sly data-sly-resource="${'rules' @ resourceType='studio/forms/components/rules'}"></sly>
</div>
''',
'fileupload': '''
<div class="studio-form__field studio-form__field--file" data-studio-field="${properties.name}">
    <label class="studio-form__label" for="${resource.name}-${properties.name}">${properties.label || 'File'}<span class="studio-form__required" data-sly-test="${properties.required}" aria-hidden="true"> *</span></label>
    <input class="studio-form__input studio-form__file" id="${resource.name}-${properties.name}" type="file" name="${properties.name}"
           accept="${properties.accept}" data-studio-max-size="${properties.maxSize || 10}"
           data-sly-attribute.multiple="${properties.multiple ? 'multiple' : ''}"
           data-sly-attribute.required="${properties.required ? 'required' : ''}"
           data-studio-messages="${properties.requiredMessage}|"/>
    <p class="studio-form__help" data-sly-test="${properties.help}">${properties.help}</p>
    <p class="studio-form__help" data-sly-test="${!properties.help}">Up to ${properties.maxSize || 10} MB<sly data-sly-test="${properties.accept}">; ${properties.accept}</sly>.</p>
    <p class="studio-form__error" data-studio-error aria-live="polite"></p>
    <sly data-sly-resource="${'rules' @ resourceType='studio/forms/components/rules'}"></sly>
</div>
''',
'dropdown': '''
<div class="studio-form__field studio-form__field--dropdown" data-studio-field="${properties.name}">
    <label class="studio-form__label" for="${resource.name}-${properties.name}">${properties.label || 'Choose'}<span class="studio-form__required" data-sly-test="${properties.required}" aria-hidden="true"> *</span></label>
    <select class="studio-form__input studio-form__select" id="${resource.name}-${properties.name}" name="${properties.name}"
            data-sly-attribute.required="${properties.required ? 'required' : ''}"
            data-studio-messages="${properties.requiredMessage}|">
        <option value="" data-sly-test="${properties.placeholder}">${properties.placeholder}</option>
        <option data-sly-repeat.o="${properties.options}" value="${o}" data-sly-attribute.selected="${o == properties.value ? 'selected' : ''}">${o}</option>
    </select>
    <p class="studio-form__help" data-sly-test="${properties.help}">${properties.help}</p>
    <p class="studio-form__error" data-studio-error aria-live="polite"></p>
    <sly data-sly-resource="${'rules' @ resourceType='studio/forms/components/rules'}"></sly>
</div>
''',
'checkbox': '''
<div class="studio-form__field studio-form__field--checkbox" data-studio-field="${properties.name}">
    <label class="studio-form__check" for="${resource.name}-${properties.name}">
        <input class="studio-form__checkbox" id="${resource.name}-${properties.name}" type="checkbox" name="${properties.name}" value="${properties.checkedValue || 'yes'}"
               data-sly-attribute.checked="${properties.value ? 'checked' : ''}"
               data-sly-attribute.required="${properties.required ? 'required' : ''}"
               data-studio-messages="${properties.requiredMessage}|"/>
        <span>${properties.label || 'Checkbox'}<span class="studio-form__required" data-sly-test="${properties.required}" aria-hidden="true"> *</span></span>
    </label>
    <p class="studio-form__help" data-sly-test="${properties.help}">${properties.help}</p>
    <p class="studio-form__error" data-studio-error aria-live="polite"></p>
    <sly data-sly-resource="${'rules' @ resourceType='studio/forms/components/rules'}"></sly>
</div>
''',
'radiogroup': '''
<fieldset class="studio-form__field studio-form__field--radiogroup" data-studio-field="${properties.name}" data-sly-attribute.data-studio-required="${properties.required ? 'true' : ''}" data-studio-messages="${properties.requiredMessage}|">
    <legend class="studio-form__label">${properties.label || 'Choose one'}<span class="studio-form__required" data-sly-test="${properties.required}" aria-hidden="true"> *</span></legend>
    <label class="studio-form__check" data-sly-repeat.o="${properties.options}">
        <input class="studio-form__radio" type="radio" name="${properties.name}" value="${o}" data-sly-attribute.checked="${o == properties.value ? 'checked' : ''}"/>
        <span>${o}</span>
    </label>
    <p class="studio-form__help" data-sly-test="${properties.help}">${properties.help}</p>
    <p class="studio-form__error" data-studio-error aria-live="polite"></p>
    <sly data-sly-resource="${'rules' @ resourceType='studio/forms/components/rules'}"></sly>
</fieldset>
''',
'checkboxgroup': '''
<fieldset class="studio-form__field studio-form__field--checkboxgroup" data-studio-field="${properties.name}" data-studio-group data-sly-attribute.data-studio-required="${properties.required ? 'true' : ''}" data-studio-messages="${properties.requiredMessage}|">
    <legend class="studio-form__label">${properties.label || 'Choose'}<span class="studio-form__required" data-sly-test="${properties.required}" aria-hidden="true"> *</span></legend>
    <label class="studio-form__check" data-sly-repeat.o="${properties.options}">
        <input class="studio-form__checkbox" type="checkbox" name="${properties.name}" value="${o}"/>
        <span>${o}</span>
    </label>
    <p class="studio-form__help" data-sly-test="${properties.help}">${properties.help}</p>
    <p class="studio-form__error" data-studio-error aria-live="polite"></p>
    <sly data-sly-resource="${'rules' @ resourceType='studio/forms/components/rules'}"></sly>
</fieldset>
''',
'hidden': '''
<div class="studio-form__field studio-form__field--hidden" data-studio-field="${properties.name}" hidden data-sly-attribute.hidden="${wcmmode.edit ? '' : 'hidden'}">
    <span class="studio-form__text studio-form__text--empty" data-sly-test="${wcmmode.edit}">Hidden field ${properties.name}: ${properties.value || 'the page’s address'}</span>
    <input type="hidden" name="${properties.name}" value="${properties.value}" data-sly-attribute.data-studio-page="${properties.value ? '' : 'true'}"/>
    <sly data-sly-resource="${'rules' @ resourceType='studio/forms/components/rules'}"></sly>
</div>
''',
'text': '''
<div class="studio-form__field studio-form__field--text" data-studio-field="${properties.name || resource.name}">
    <div class="studio-form__text" data-sly-test="${properties.text}">${properties.text @ context='html'}</div>
    <div class="studio-form__text studio-form__text--empty" data-sly-test="${!properties.text && wcmmode.edit}">Text: open the dialog to write it.</div>
    <sly data-sly-resource="${'rules' @ resourceType='studio/forms/components/rules'}"></sly>
</div>
''',
'submit': '''
<div class="studio-form__actions">
    <button class="studio-form__submit" type="submit">${properties.label || 'Submit'}</button>
</div>
''',
}

for n,h in FIELD_HTML.items():
    w(f'{J}/components/{n}/{n}.html', h)

component('textfield','Text field','A single line of text, with optional length and pattern checks.')
component('textarea','Text area','Several lines of text.')
component('email','Email','An email address; checked for shape.')
component('number','Number','A number, with optional minimum, maximum and step.')
component('date','Date','A day, picked from a calendar; optional earliest and latest day.')
component('fileupload','File upload','One or more files, kept with the submission.')
component('dropdown','Dropdown','One choice from a list.')
component('checkbox','Checkbox','A yes/no box; required means it must be ticked.')
component('radiogroup','Radio group','One choice from a few, shown as radio buttons.')
component('checkboxgroup','Checkbox group','Several choices from a list, shown as checkboxes.')
component('hidden','Hidden field','A value the visitor does not see: a campaign code, or the page the form is on.')
component('text','Text','A paragraph of copy between fields (instructions, headings).')
component('submit','Submit button','Sends the form.')

RULES_TAB = tab('rules', 'Rules', field('rules', 'Rules', rt='granite/ui/components/coral/foundation/form/multifield', composite='{Boolean}true',
    fieldDescription='Each rule: what happens to this field when another field has a value. Several rules of the same kind must all hold.',
    children='''<field
    jcr:primaryType="nt:unstructured"
    sling:resourceType="granite/ui/components/coral/foundation/container"
    name="./rules">
    <items jcr:primaryType="nt:unstructured">
        <action jcr:primaryType="nt:unstructured" sling:resourceType="granite/ui/components/coral/foundation/form/select" fieldLabel="Then" name="./action">
            <items jcr:primaryType="nt:unstructured">
                <show jcr:primaryType="nt:unstructured" text="Show this field only when" value="show" selected="{Boolean}true"/>
                <hide jcr:primaryType="nt:unstructured" text="Hide this field when" value="hide"/>
                <require jcr:primaryType="nt:unstructured" text="Require this field when" value="require"/>
                <disable jcr:primaryType="nt:unstructured" text="Disable this field when" value="disable"/>
            </items>
        </action>
        <field jcr:primaryType="nt:unstructured" sling:resourceType="granite/ui/components/coral/foundation/form/textfield" fieldLabel="Field" fieldDescription="The name of the other field." name="./field" required="{Boolean}true"/>
        <op jcr:primaryType="nt:unstructured" sling:resourceType="granite/ui/components/coral/foundation/form/select" fieldLabel="Condition" name="./op">
            <items jcr:primaryType="nt:unstructured">
                <is jcr:primaryType="nt:unstructured" text="is" value="is" selected="{Boolean}true"/>
                <isnot jcr:primaryType="nt:unstructured" text="is not" value="isnot"/>
                <contains jcr:primaryType="nt:unstructured" text="contains" value="contains"/>
                <empty jcr:primaryType="nt:unstructured" text="is empty" value="empty"/>
                <notempty jcr:primaryType="nt:unstructured" text="is not empty" value="notempty"/>
                <gt jcr:primaryType="nt:unstructured" text="is greater than" value="gt"/>
                <lt jcr:primaryType="nt:unstructured" text="is less than" value="lt"/>
            </items>
        </op>
        <value jcr:primaryType="nt:unstructured" sling:resourceType="granite/ui/components/coral/foundation/form/textfield" fieldLabel="Value" fieldDescription="For a checkbox: its value when ticked (“yes” unless changed)." name="./value"/>
    </items>
</field>
'''))

VALID_TEXT = (field('minLength','Minimum length', rt='granite/ui/components/coral/foundation/form/numberfield', min='0')
  + field('maxLength','Maximum length', rt='granite/ui/components/coral/foundation/form/numberfield', min='1')
  + field('pattern','Pattern', fieldDescription='A regular expression the value must match, e.g. ^[A-Z]{2}[0-9]{4}$')
  + field('patternMessage','Message when the pattern fails'))

dialog('textfield','Text field',[tab('fieldtab','Field', COMMON('textfield')+DEFAULT+HELP), tab('validation','Validation', REQ+VALID_TEXT), RULES_TAB])
dialog('textarea','Text area',[tab('fieldtab','Field', COMMON('textarea')+field('rows','Rows', rt='granite/ui/components/coral/foundation/form/numberfield', min='2', value='4')+DEFAULT+HELP), tab('validation','Validation', REQ+field('minLength','Minimum length', rt='granite/ui/components/coral/foundation/form/numberfield', min='0')+field('maxLength','Maximum length', rt='granite/ui/components/coral/foundation/form/numberfield', min='1')), RULES_TAB])
dialog('email','Email',[tab('fieldtab','Field', COMMON('email')+DEFAULT+HELP), tab('validation','Validation', REQ+field('typeMessage','Message when not an email', fieldDescription='Default: “Enter a valid email address.”')), RULES_TAB])
dialog('number','Number',[tab('fieldtab','Field', COMMON('number')+DEFAULT+HELP), tab('validation','Validation', REQ+field('min','Minimum', rt='granite/ui/components/coral/foundation/form/numberfield')+field('max','Maximum', rt='granite/ui/components/coral/foundation/form/numberfield')+field('step','Step', rt='granite/ui/components/coral/foundation/form/numberfield', fieldDescription='1 for whole numbers; empty for any number.')+field('rangeMessage','Message when out of range')), RULES_TAB])
dialog('date','Date',[tab('fieldtab','Field', COMMON('date')+field('value','Default value', fieldDescription='yyyy-mm-dd')+HELP), tab('validation','Validation', REQ+field('minDate','Earliest day', fieldDescription='yyyy-mm-dd')+field('maxDate','Latest day', fieldDescription='yyyy-mm-dd')+field('rangeMessage','Message when out of range')), RULES_TAB])
dialog('fileupload','File upload',[tab('fieldtab','Field', COMMON('fileupload')+field('accept','Accepted files', fieldDescription='Comma-separated: .pdf, .jpg, image/*, application/pdf. Empty: any file.')+field('maxSize','Largest file (MB)', rt='granite/ui/components/coral/foundation/form/numberfield', min='1', value='10')+checkbox('multiple','Several files')+HELP), tab('validation','Validation', REQ), RULES_TAB])
OPTIONS = field('options','Options', rt='granite/ui/components/coral/foundation/form/multifield', fieldDescription='One per row. The text is also the value stored.', children=field('field','Option', rt='granite/ui/components/coral/foundation/form/textfield').replace('name="./field"','name="./options"').replace('<field','<field').replace('fieldLabel="Option"','fieldLabel="Option"'))
dialog('dropdown','Dropdown',[tab('fieldtab','Field', COMMON('dropdown')+field('placeholder','First, empty entry', fieldDescription='e.g. “Choose…”; leaves the field unanswered until a choice is made.')+OPTIONS+DEFAULT+HELP), tab('validation','Validation', REQ), RULES_TAB])
dialog('checkbox','Checkbox',[tab('fieldtab','Field', COMMON('checkbox')+field('checkedValue','Value when ticked', fieldDescription='Stored in the submission; default “yes”.')+checkbox('value','Ticked by default')+HELP), tab('validation','Validation', REQ), RULES_TAB])
dialog('checkboxgroup','Checkbox group',[tab('fieldtab','Field', COMMON('checkboxgroup')+OPTIONS+HELP), tab('validation','Validation', REQ.replace('Required', 'At least one must be ticked')), RULES_TAB])
dialog('hidden','Hidden field',[tab('fieldtab','Field', field('name', 'Name', fieldDescription='The key of this field in the submission (letters, digits, _ and -). Required.', required='{Boolean}true')+field('value','Value', fieldDescription='Stored as is. Empty: the address of the page the form is on.'))])
dialog('radiogroup','Radio group',[tab('fieldtab','Field', COMMON('radiogroup')+OPTIONS+DEFAULT+HELP), tab('validation','Validation', REQ), RULES_TAB])
dialog('text','Text',[tab('fieldtab','Text', field('text','Text', rt='cq/gui/components/authoring/dialog/richtext', useFixedInlineToolbar='{Boolean}true')+field('name','Name', fieldDescription='Optional; lets rules refer to this text.')), RULES_TAB])
dialog('submit','Submit button',[tab('fieldtab','Button', field('label','Label', value='Submit'))])

# ------------------------------------------------------------------ rules
# the rules of a field live in its `rules` child (one node per rule); this script renders them as JSON
# for forms.js. Included by every field with a forced resourceType, so a field without rules renders nothing.
component('rules','Rules','The rules of a field, as JSON for the form library.', group='.hidden')
w(f'{J}/components/rules/rules.html', '''
<span hidden data-studio-rules data-sly-test="${resource.listChildren}" data-sly-list.r="${resource.listChildren}"><span data-studio-rule data-action="${r.valueMap.action}" data-field="${r.valueMap.field}" data-op="${r.valueMap.op}" data-value="${r.valueMap.value}"></span></span>
''')

RULES_INCLUDE = """    <sly data-sly-resource="${'rules' @ resourceType='studio/forms/components/rules'}"></sly>
"""

# ------------------------------------------------------------------ containers
component('form','Form','The form itself: holds the fields; where the submission goes and what happens after.', container=True, supertype='wcm/foundation/components/responsivegrid')
w(f'{J}/components/form/form.html', '''
<!--/* The form container: a <form> around a layout grid of fields. Submission and validation: studio.forms (forms.js). */-->
<form class="studio-form cmp-container" method="post" action="${properties.action || '/bin/studio/forms/submit'}" novalidate
      data-studio-form="${resource.path}"
      data-studio-after="${properties.afterSubmit || 'message'}"
      data-studio-message="${properties.thankYou || 'Thank you — your form was sent.'}"
      data-studio-redirect="${properties.redirect}"
      data-sly-attribute.data-studio-steps="${properties.steps ? 'true' : ''}"
      data-sly-attribute.data-studio-editing="${wcmmode.edit ? 'true' : ''}"
      data-sly-attribute.data-studio-preview="${wcmmode.edit || wcmmode.preview ? 'true' : ''}">
    <input type="text" name="studio:website" class="studio-form__trap" tabindex="-1" autocomplete="off" aria-hidden="true"/>
    <sly data-sly-resource="${resource.path @ resourceType='wcm/foundation/components/responsivegrid'}"></sly>
    <p class="studio-form__status" data-studio-status aria-live="polite"></p>
</form>
''')
dialog('form','Form',[
    tab('steps','Steps', checkbox('steps', 'One step per panel', 'The top-level panels become steps with Next and Back; each step is checked before the next.')),
    tab('submission','After submit',
        field('afterSubmit','What happens after a submission', rt='granite/ui/components/coral/foundation/form/select', children='''<items jcr:primaryType="nt:unstructured">
    <message jcr:primaryType="nt:unstructured" text="Show a message" value="message" selected="{Boolean}true"/>
    <redirect jcr:primaryType="nt:unstructured" text="Go to a page" value="redirect"/>
</items>
''')
        + field('thankYou','Message', fieldDescription='Shown in place of the form.', value='Thank you — your form was sent.')
        + field('redirect','Page', rt='granite/ui/components/coral/foundation/form/pathfield', rootPath='/content', fieldDescription='Where to go after a submission, when “Go to a page” is chosen.')),
    tab('record','Content Fragment',
        field('recordModel','Create a content fragment of the model', rt='granite/ui/components/coral/foundation/form/pathfield', rootPath='/conf', fieldDescription='Every submission also becomes a content fragment of this model (under /conf/…/settings/dam/cfm/models). Empty: no content fragments.')
        + field('recordFolder','In the folder', rt='granite/ui/components/coral/foundation/form/pathfield', rootPath='/content/dam', fieldDescription='Where the content fragments go, under /content/dam. Created when missing.')
        + field('recordTitle','Content fragment title', fieldDescription='Fields in braces, e.g. {name} — {destination}; also {form} and {date}. Empty: the form title and the time.')
        + field('recordMapping', 'Fields to elements', rt='granite/ui/components/coral/foundation/form/multifield', composite='{Boolean}true',
            fieldDescription='A field goes to the element of the same name. Here: other names, or leave Element empty to keep a field out of the content fragment. Files stay with the submission.',
            children='''<field
    jcr:primaryType="nt:unstructured"
    sling:resourceType="granite/ui/components/coral/foundation/container"
    name="./recordMapping">
    <items jcr:primaryType="nt:unstructured">
        <field jcr:primaryType="nt:unstructured" sling:resourceType="granite/ui/components/coral/foundation/form/textfield" fieldLabel="Field" fieldDescription="The form field's name." name="./field" required="{Boolean}true"/>
        <element jcr:primaryType="nt:unstructured" sling:resourceType="granite/ui/components/coral/foundation/form/textfield" fieldLabel="Element" fieldDescription="The model element's name; empty leaves the field out." name="./element"/>
    </items>
</field>
''')),
    tab('actions','Actions',
        field('notifyTo','Email the submission to', fieldDescription='One or more addresses, separated by commas. Needs a mail server (System › Mail).')
        + field('notifySubject','Subject', fieldDescription='Default: “New submission: ” and the form’s title.')
        + field('flowModel','Start a flow', rt='granite/ui/components/coral/foundation/form/pathfield', rootPath='/var/workflow/models', fieldDescription='The flow runs on the submission.')
        + field('webhookUrl','Send to a web address', fieldDescription='The submission is POSTed there as JSON (https://…).')),
])
component('panel','Panel','A section of the form: a heading over a group of fields. One step when the form has steps.', container=True, supertype='wcm/foundation/components/responsivegrid')
w(f'{J}/components/panel/panel.html', '''
<fieldset class="studio-form__panel cmp-container" data-studio-panel="${properties.name || resource.name}" data-studio-field="${properties.name || resource.name}" data-studio-title="${properties.title}">
    <legend class="studio-form__panel-title" data-sly-test="${properties.title}">${properties.title}</legend>
    <p class="studio-form__panel-text" data-sly-test="${properties.description}">${properties.description}</p>
    <sly data-sly-resource="${resource.path @ resourceType='wcm/foundation/components/responsivegrid'}"></sly>
    <sly data-sly-resource="${'rules' @ resourceType='studio/forms/components/rules'}"></sly>
</fieldset>
''')
dialog('panel','Panel',[tab('fieldtab','Panel', field('title','Title')+field('description','Description')+field('name','Name', fieldDescription='Identifies the panel in rules and steps; optional.')), RULES_TAB])

# ------------------------------------------------------------------ the page
component('formpage','Form page','The page of a form: the site head, the form, the site footer.', group='.hidden', supertype='core/wcm/components/page/v3/page')
w(f'{J}/components/formpage/formpage.html', '''
<!--/*
    Sensible Studio: the page of a form. The page's head (Core Components page v3 head with the site's libraries)
    and a body holding the template's structure: the form container with the fields. The form's own client
    library (studio.forms: validation, submission) is loaded by the page policy's clientlibs.
*/-->
<!DOCTYPE HTML>
<html data-sly-use.page="com.adobe.cq.wcm.core.components.models.Page" lang="${page.language}"
      data-sly-use.pwa="com.adobe.cq.wcm.core.components.models.PWA"
      data-sly-use.head="/apps/core/wcm/components/page/v3/page/head.html"
      data-sly-use.footer="/apps/core/wcm/components/page/v3/page/footer.html">
    <head data-sly-call="${head.head @ page = page, pwa = pwa}"></head>
    <body class="studio-formpage ${page.cssClassNames}"
          id="${page.id}"
          data-cmp-data-layer-enabled="${page.data ? true : false}"
          data-cmp-data-layer-name="${page.dataLayerName @ context='scriptToken'}">
        <main class="studio-formpage__main" data-sly-use.templatedContainer="com.day.cq.wcm.foundation.TemplatedContainer">
            <sly data-sly-repeat.child="${templatedContainer.structureResources}"
                 data-sly-resource="${child @ resourceType=child.resourceType, decorationTagName='div'}"></sly>
        </main>
        <sly data-sly-call="${footer.footer @ page = page, pwa = pwa}"></sly>
    </body>
</html>
''')

# ------------------------------------------------------------------ client library
w(f'{J}/clientlibs/forms/.content.xml', X+'''<jcr:root xmlns:cq="http://www.day.com/jcr/cq/1.0" xmlns:jcr="http://www.jcp.org/jcr/1.0"
    jcr:primaryType="cq:ClientLibraryFolder"
    categories="[studio.forms]"
    allowProxy="{Boolean}true"/>
''')
w(f'{J}/clientlibs/forms/js.txt', '#base=js\nforms.js\n')
w(f'{J}/clientlibs/forms/css.txt', '#base=css\nforms.css\n')
w(f'{J}/clientlibs/forms/js/forms.js', open(os.path.join(HERE, 'forms.js')).read())
w(f'{J}/clientlibs/forms/css/forms.css', open(os.path.join(HERE, 'forms.css')).read())

# ------------------------------------------------------------------ template and policies (/conf/global)
C='/conf/global/settings/wcm'
w(f'{C}/templates/form/.content.xml', X+f'''<jcr:root {NS}
    jcr:primaryType="cq:Template"
    allowedPaths="[/content/forms(/.*)?]">
    <jcr:content
        jcr:primaryType="cq:PageContent"
        jcr:title="Form"
        jcr:description="A form: fields in a layout grid, with a Submit button. For pages under Forms."
        status="enabled"/>
</jcr:root>
''')
w(f'{C}/templates/form/structure/.content.xml', X+f'''<jcr:root {NS}
    jcr:primaryType="cq:Page">
    <jcr:content
        cq:deviceGroups="[mobile/groups/responsive]"
        cq:template="/conf/global/settings/wcm/templates/form"
        jcr:primaryType="cq:PageContent"
        sling:resourceType="studio/forms/components/formpage">
        <root
            jcr:primaryType="nt:unstructured"
            sling:resourceType="studio/forms/components/form"
            layout="responsiveGrid"
            editable="{{Boolean}}true"/>
        <cq:responsive jcr:primaryType="nt:unstructured">
            <breakpoints jcr:primaryType="nt:unstructured">
                <phone jcr:primaryType="nt:unstructured" title="Smaller Screen" width="{{Long}}768"/>
                <tablet jcr:primaryType="nt:unstructured" title="Tablet" width="{{Long}}1200"/>
            </breakpoints>
        </cq:responsive>
    </jcr:content>
</jcr:root>
''')
w(f'{C}/templates/form/initial/.content.xml', X+f'''<jcr:root {NS}
    jcr:primaryType="cq:Page">
    <jcr:content
        cq:template="/conf/global/settings/wcm/templates/form"
        jcr:primaryType="cq:PageContent"
        sling:resourceType="studio/forms/components/formpage">
        <root
            jcr:primaryType="nt:unstructured"
            sling:resourceType="studio/forms/components/form"
            layout="responsiveGrid">
            <name jcr:primaryType="nt:unstructured" sling:resourceType="studio/forms/components/textfield" name="name" label="Name" required="{{Boolean}}true"/>
            <email jcr:primaryType="nt:unstructured" sling:resourceType="studio/forms/components/email" name="email" label="Email" required="{{Boolean}}true"/>
            <message jcr:primaryType="nt:unstructured" sling:resourceType="studio/forms/components/textarea" name="message" label="Message"/>
            <submit jcr:primaryType="nt:unstructured" sling:resourceType="studio/forms/components/submit" label="Send"/>
        </root>
    </jcr:content>
</jcr:root>
''')
w(f'{C}/templates/form/policies/.content.xml', X+f'''<jcr:root {NS}
    jcr:primaryType="cq:Page">
    <jcr:content
        cq:policy="studio/forms/components/formpage/default"
        jcr:primaryType="nt:unstructured"
        sling:resourceType="wcm/core/components/policies/mappings">
        <root
            cq:policy="studio/forms/components/form/default"
            jcr:primaryType="nt:unstructured"
            sling:resourceType="wcm/core/components/policies/mapping">
            <studio jcr:primaryType="nt:unstructured">
                <forms jcr:primaryType="nt:unstructured">
                    <components jcr:primaryType="nt:unstructured">
                        <panel
                            cq:policy="studio/forms/components/form/default"
                            jcr:primaryType="nt:unstructured"
                            sling:resourceType="wcm/core/components/policies/mapping"/>
                    </components>
                </forms>
            </studio>
        </root>
    </jcr:content>
</jcr:root>
''')
w(f'{C}/policies/studio/forms/components/.content.xml', X+f'''<jcr:root {NS}
    jcr:primaryType="nt:unstructured">
    <formpage jcr:primaryType="nt:unstructured">
        <default
            jcr:primaryType="nt:unstructured"
            jcr:title="Form page"
            jcr:description="The site's libraries plus the form library."
            sling:resourceType="wcm/core/components/policy/policy"
            clientlibs="[studio.forms]"/>
    </formpage>
    <form jcr:primaryType="nt:unstructured">
        <default
            jcr:primaryType="nt:unstructured"
            jcr:title="Form fields"
            jcr:description="Every form component may be placed in a form or a panel."
            sling:resourceType="wcm/core/components/policy/policy"
            components="[group:Forms]"/>
    </form>
</jcr:root>
''')

# ------------------------------------------------------------------ content roots and the sample
w('/content/forms/.content.xml', X+f'''<jcr:root {NS}
    jcr:primaryType="cq:Page">
    <jcr:content
        jcr:primaryType="cq:PageContent"
        jcr:title="Forms"
        sling:resourceType="wcm/foundation/components/basicpage/v1/basicpage"
        cq:conf="/conf/global"
        cq:allowedTemplates="[/conf/global/settings/wcm/templates/form]"/>
</jcr:root>
''')
w('/content/forms/wknd/.content.xml', X+f'''<jcr:root {NS}
    jcr:primaryType="cq:Page">
    <jcr:content
        jcr:primaryType="cq:PageContent"
        jcr:title="WKND"
        sling:resourceType="wcm/foundation/components/basicpage/v1/basicpage"
        cq:conf="/conf/global"
        cq:allowedTemplates="[/conf/global/settings/wcm/templates/form]"/>
</jcr:root>
''')
w('/content/forms/wknd/contact/.content.xml', X+f'''<jcr:root {NS}
    jcr:primaryType="cq:Page">
    <jcr:content
        cq:template="/conf/global/settings/wcm/templates/form"
        jcr:primaryType="cq:PageContent"
        jcr:title="Contact us"
        sling:resourceType="studio/forms/components/formpage">
        <root
            jcr:primaryType="nt:unstructured"
            sling:resourceType="studio/forms/components/form"
            layout="responsiveGrid"
            afterSubmit="message"
            thankYou="Thanks — we read every message and answer within two days.">
            <intro jcr:primaryType="nt:unstructured" sling:resourceType="studio/forms/components/text" text="&lt;p&gt;Questions about a trip, a magazine story or the site? Write to us.&lt;/p&gt;"/>
            <name jcr:primaryType="nt:unstructured" sling:resourceType="studio/forms/components/textfield" name="name" label="Your name" required="{{Boolean}}true" maxLength="{{Long}}80">
                <cq:responsive jcr:primaryType="nt:unstructured"><default jcr:primaryType="nt:unstructured" width="6" offset="0"/></cq:responsive>
            </name>
            <email jcr:primaryType="nt:unstructured" sling:resourceType="studio/forms/components/email" name="email" label="Email" required="{{Boolean}}true" placeholder="you@example.com">
                <cq:responsive jcr:primaryType="nt:unstructured"><default jcr:primaryType="nt:unstructured" width="6" offset="0"/></cq:responsive>
            </email>
            <topic jcr:primaryType="nt:unstructured" sling:resourceType="studio/forms/components/dropdown" name="topic" label="Topic" placeholder="Choose…" required="{{Boolean}}true" options="[A trip,The magazine,The website,Something else]"/>
            <travellers jcr:primaryType="nt:unstructured" sling:resourceType="studio/forms/components/number" name="travellers" label="How many of you are travelling?" min="{{Long}}1" max="{{Long}}20" step="{{Long}}1">
                <rules jcr:primaryType="nt:unstructured">
                    <item0 jcr:primaryType="nt:unstructured" action="show" field="topic" op="is" value="A trip"/>
                    <item1 jcr:primaryType="nt:unstructured" action="require" field="topic" op="is" value="A trip"/>
                </rules>
            </travellers>
            <message jcr:primaryType="nt:unstructured" sling:resourceType="studio/forms/components/textarea" name="message" label="Message" required="{{Boolean}}true" rows="{{Long}}6" minLength="{{Long}}10"/>
            <newsletter jcr:primaryType="nt:unstructured" sling:resourceType="studio/forms/components/checkbox" name="newsletter" label="Send me the WKND newsletter"/>
            <submit jcr:primaryType="nt:unstructured" sling:resourceType="studio/forms/components/submit" label="Send"/>
        </root>
    </jcr:content>
</jcr:root>
''')

# a two-step form: the top-level panels become steps
w('/content/forms/wknd/booking/.content.xml', X+f'''<jcr:root {NS}
    jcr:primaryType="cq:Page">
    <jcr:content
        cq:template="/conf/global/settings/wcm/templates/form"
        jcr:primaryType="cq:PageContent"
        jcr:title="Book a trip"
        sling:resourceType="studio/forms/components/formpage">
        <root
            jcr:primaryType="nt:unstructured"
            sling:resourceType="studio/forms/components/form"
            layout="responsiveGrid"
            steps="{{Boolean}}true"
            afterSubmit="message"
            thankYou="Booked — we will confirm by email.">
            <about jcr:primaryType="nt:unstructured" sling:resourceType="studio/forms/components/panel" title="About you" layout="responsiveGrid">
                <name jcr:primaryType="nt:unstructured" sling:resourceType="studio/forms/components/textfield" name="name" label="Your name" required="{{Boolean}}true"/>
                <email jcr:primaryType="nt:unstructured" sling:resourceType="studio/forms/components/email" name="email" label="Email" required="{{Boolean}}true"/>
            </about>
            <trip jcr:primaryType="nt:unstructured" sling:resourceType="studio/forms/components/panel" title="Your trip" layout="responsiveGrid">
                <destination jcr:primaryType="nt:unstructured" sling:resourceType="studio/forms/components/dropdown" name="destination" label="Destination" required="{{Boolean}}true" options="[Mont Blanc,Bali,Yosemite]"/>
                <travellers jcr:primaryType="nt:unstructured" sling:resourceType="studio/forms/components/number" name="travellers" label="Travellers" min="{{Long}}1" max="{{Long}}12"/>
                <when jcr:primaryType="nt:unstructured" sling:resourceType="studio/forms/components/date" name="when" label="When do you want to go?" required="{{Boolean}}true"/>
                <passport jcr:primaryType="nt:unstructured" sling:resourceType="studio/forms/components/fileupload" name="passport" label="A scan of your passport" accept=".pdf,image/*" maxSize="{{Long}}2"/>
            </trip>
            <submit jcr:primaryType="nt:unstructured" sling:resourceType="studio/forms/components/submit" label="Book"/>
        </root>
    </jcr:content>
</jcr:root>
''')

# intermediate folders as sling:Folder, so nt:unstructured children are allowed under them
for folder in ['/libs/studio','/libs/studio/forms','/libs/studio/forms/components','/libs/studio/forms/clientlibs',
               '/conf/global/settings/wcm/policies/studio','/conf/global/settings/wcm/policies/studio/forms']:
    w(folder+'/.content.xml', X+'<jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0" xmlns:sling="http://sling.apache.org/jcr/sling/1.0"\n    jcr:primaryType="sling:Folder"/>\n')

w('META-INF/vault/filter.xml', X+'''<workspaceFilter version="1.0">
    <filter root="/libs/studio/forms"/>
    <filter root="/conf/global/settings/wcm/templates/form"/>
    <filter root="/conf/global/settings/wcm/policies/studio/forms"/>
    <filter root="/content/forms" mode="merge"/>
</workspaceFilter>
''')
w('META-INF/vault/properties.xml', '''<?xml version="1.0" encoding="utf-8" standalone="no"?>
<!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">
<properties>
<entry key="group">io.sensible.studio</entry>
<entry key="name">studio-forms-content</entry>
<entry key="version">0.5.1</entry>
<entry key="description">Sensible Studio Forms: the Form template, the form and field components with their dialogs, the studio.forms client library, and the Forms root with a sample form. Written for Sensible Studio; contains no Adobe content.</entry>
<entry key="createdBy">Sensible Studio</entry>
<entry key="packageType">mixed</entry>
<entry key="requiresRoot">false</entry>
</properties>
''')
print('generated')
