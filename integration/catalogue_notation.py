"""Conservative drawing notation: exact symbols/numbers, explicit shape, unchanged details."""
import re

def compact(value):
    # Never join two numeric fields: '7 05' is not the specification '705'.
    return re.sub(r'(?<!\d)\s+|\s+(?!\d)','',str(value).casefold().replace('×','x'))

def recognize(description,names):
    key=compact(description);exact=[name for name in names if compact(name)==key]
    if exact:return {'keys':exact,'details':'','rule':'spaties'}
    gain=re.fullmatch(r'gain[ø∅⌀](\d+(?:[.,]\d+)?)(-\d+(?:[.,]\d+)?(?:mm)?)',key)
    if gain:
        base='gainø'+gain[1];matches=[name for name in names if compact(name)==base]
        if matches:return {'keys':matches,'details':gain[2],'rule':'gain-maatnotatie'}
    shape=None
    number=r'\d+(?:[.,]\d+)?'
    if re.fullmatch(r'sparing[dø∅⌀]'+number+r'(?:[x*]'+number+r'){0,2}(?:mm)?',key):shape='sparingrond'
    elif re.fullmatch(r'sparing'+number+r'[x*]'+number+r'[x*]'+number+r'(?:mm)?',key):shape='sparingrechthoek'
    if shape:
        matches=[name for name in names if compact(name)==shape]
        if matches:return {'keys':matches,'details':str(description).strip(),'rule':'expliciete-sparingsvorm'}
    return None
