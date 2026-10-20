"""Approved aliases for an explicit drawing status cell (not arbitrary page text)."""
def final_status_token(token):
    # Whitespace/punctuation removal and negative-status checks belong to the caller.
    # VORM identifies the drawing type; it does not weaken a DEFINITIEF status.
    return token[4:] if token.startswith('vormdef') else token
