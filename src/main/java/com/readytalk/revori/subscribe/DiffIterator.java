package com.readytalk.revori.subscribe;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import com.readytalk.revori.DiffResult;
import com.readytalk.revori.QueryResult;
import com.readytalk.revori.Revision;
import com.readytalk.revori.Table;
import com.readytalk.revori.util.SetMultimap;

public class DiffIterator<Context> {
  private static boolean DebugThreads = true;

  private enum State { Diff, Matchers, Result, End; };

  private final Revision base;
  private final Revision head;
  private final DiffResult diffResult;
  private final SetMultimap<Table, Matcher<Context>> matchers;
  private Set<Matcher<Context>> visited;
  private Iterator<Matcher<Context>> matchIterator;
  private Matcher matcher;
  private State state;
  private QueryResult queryResult;
  private Object[] row;
  private Thread thread;

  public DiffIterator(Revision base,
                      Revision head,
                      SetMultimap<Table, Matcher<Context>> matchers)
  {
    this.base = base;
    this.head = head;
    this.diffResult = base.diff(head, true);
    this.matchers = matchers;
    this.state = State.Diff;
  }

  public boolean next(Context context) {
    if (DebugThreads) {
      if (thread == null) {
        thread = Thread.currentThread();
      } else if (thread != Thread.currentThread()) {
        throw new IllegalStateException
          ("expected " + thread + " got " + Thread.currentThread());
      }
    }

    while (true) {
      switch (state) {
      case Diff: {
        DiffResult.Type type = diffResult.next();
        switch (type) {
        case Key: {
          Object base = diffResult.base();
          Object fork = diffResult.fork();
          diffResult.skip();

          Set<Matcher<Context>> set = matchers.get
            ((Table) (base == null ? fork : base));

          if (set != null) {
            matchIterator = set.iterator();
            state = State.Matchers;
          }
        } break;

        case End: {
          state = State.End;
        } break;

        default:
          throw new RuntimeException("unexpected type: " + type);
        }
      } break;

      case Matchers: {
        if (matchIterator.hasNext()) {
          matcher = matchIterator.next();
          if (visited == null || ! visited.contains(matcher)) {
            if (visited == null) {
              visited = new HashSet<Matcher<Context>>();
            }
            visited.add(matcher);
            queryResult = base.diff(head, matcher.query, matcher.params);
            state = State.Result;            
          }
        } else {
          matchIterator = null;
          state = State.Diff;
        }
      } break;

      case Result: {
        QueryResult.Type type = queryResult.nextRow();
        switch (type) {
        case Inserted: {
          fillRow(matcher.query.expressions.size(), queryResult);
          matcher.listener.handleUpdate(context, row);
          return true;
        }

        case Deleted: {
          if (! queryResult.rowUpdated()) {
            fillRow(matcher.query.expressions.size(), queryResult);
            matcher.listener.handleDelete(context, row);
            return true;
          } else {
            break;
          }
        }

        case End: {
          state = State.Matchers;
        } break;

        default:
          throw new RuntimeException("unexpected type: " + type);
        }
      } break;

      case End:
        return false;

      default:
        throw new RuntimeException("unexpected state: " + state);
      }
    }
  }

  private void fillRow(int count, QueryResult result) {
    if (row == null || row.length < count) {
      row = new Object[count];
    }
    
    for (int i = 0; i < count; ++i) {
      row[i] = result.nextItem();
    }
  }

}
