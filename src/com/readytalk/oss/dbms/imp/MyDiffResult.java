package com.readytalk.oss.dbms.imp;

import static com.readytalk.oss.dbms.util.Util.list;

import com.readytalk.oss.dbms.Table;
import com.readytalk.oss.dbms.Index;
import com.readytalk.oss.dbms.DiffResult;
import com.readytalk.oss.dbms.imp.DiffIterator.DiffPair;

class MyDiffResult implements DiffResult {
  public enum State {
    Flush, FlushKey() {
      public Object fork(MyDiffResult r) {
        Node n = r.pairs[r.clientDepth].fork;
        return n == null ? null : n.key;
      }

      public Object base(MyDiffResult r) {
        Node n = r.pairs[r.clientDepth].base;
        return n == null ? null : n.key;
      }

      public void skip(MyDiffResult r) {
        int clientDepth = r.clientDepth;
        while (r.depth > clientDepth) {
          r.ascend();
        }
        r.clientDepth = r.depth;
        r.state = Iterate;
      }  
    }, Descend, Ascend, Key, Value, PostValue() {
      public Object fork(MyDiffResult r) {
        Node n = r.pairs[r.clientDepth].fork;
        return n == null ? null : n.value;
      }

      public Object base(MyDiffResult r) {
        Node n = r.pairs[r.clientDepth].base;
        return n == null ? null : n.value;
      }
    }, Iterate, End;

    public Object fork(MyDiffResult r) {
      throw new IllegalStateException();
    }

    public Object base(MyDiffResult r) {
      throw new IllegalStateException();
    }

    public void skip(MyDiffResult r) {
      throw new IllegalStateException();
    }
  }

  public final DiffIterator[] iterators = new DiffIterator[Constants.MaxDepth];
  public final DiffPair[] pairs = new DiffPair[Constants.MaxDepth];
  public final boolean[] clientHasKey = new boolean[Constants.MaxDepth];
  public State state = State.Iterate;
  public State nextState;
  public NodeStack baseStack;
  public NodeStack forkStack;
  public Table table;
  public int depth;
  public int bottom;
  public int clientDepth;

  public MyDiffResult(MyRevision base,
                      NodeStack baseStack,
                      MyRevision fork,
                      NodeStack forkStack)
  {
    iterators[0] = new DiffIterator
      (base.root,
       this.baseStack = new NodeStack(baseStack),
       fork.root,
       this.forkStack = new NodeStack(forkStack),
       list(Interval.Unbounded).iterator(),
       false);

    pairs[0] = new DiffPair();
  }

  public DiffResult.Type next() {
    while (true) {
      switch (state) {
      case Flush:
        if (! clientHasKey[clientDepth]) {
          state = State.FlushKey; 
          clientHasKey[clientDepth] = true;
          return DiffResult.Type.Key;
        } else if (clientDepth != depth) {
          if (clientDepth == Constants.TableDataDepth) {
            clientDepth += 2;
          } else {
            ++ clientDepth;
          }

          return DiffResult.Type.Descend;
        } else {
          state = nextState;
          nextState = null;
        }
        break;

      case FlushKey:
        state = State.Flush;
        break;

      case Descend:
        descend();
        state = State.Iterate;
        break;

      case Ascend:
        ascend();
        state = State.Iterate;
        break;

      case Key: {
        DiffPair pair = pairs[depth];
        if (pair.base != null && pair.fork != null) {
          if (depth > Constants.IndexDataDepth && depth == bottom) {
            if (Compare.equal(pair.base.value, pair.fork.value)) {
              state = State.Iterate;
            } else {
              nextState = State.Value;
              state = State.Flush;
            }
          } else {
            state = State.Descend;
          }
        } else {
          if (depth > Constants.IndexDataDepth && depth == bottom) {
            nextState = State.Value;
          } else {
            nextState = State.Descend;
          }
          state = State.Flush;
        }
      } break;

      case Value:
        state = State.PostValue;
        return DiffResult.Type.Value;

      case PostValue:
        state = State.Iterate;
        break;

      case Iterate: {
        clientHasKey[depth] = false;
        DiffPair pair = pairs[depth];
        if (iterators[depth].next(pair)) {
          if (depth == Constants.TableDataDepth) {
            table = (Table)
              (pair.base == null ? pair.fork.key : pair.base.key);
          } else if (depth == Constants.IndexDataDepth) {
            Index index = (Index)
              (pair.base == null ? pair.fork.key : pair.base.key);

            if (Compare.equal(index, table.primaryKey)) {
              bottom = index.columns.size() + Constants.IndexDataBodyDepth;
              descend();
            }
            break;
          }

          state = State.Key;
        } else if (depth == 0) {
          state = State.End;
        } else {
          state = State.Ascend;
          if (clientDepth == depth) {
            if (depth == Constants.IndexDataDepth + 1) {
              clientDepth -= 2;
            } else {
              -- clientDepth;
            }
            return DiffResult.Type.Ascend;
          }
        }
      } break;

      case End:
        return DiffResult.Type.End;

      default:
        throw new RuntimeException("unexpected state: " + state);
      }
    }
  }

  public void descend() {
    DiffPair pair = pairs[depth];
    Node base = pair.base;
    Node fork = pair.fork;

    ++ depth;

    iterators[depth] = new DiffIterator
      (base == null ? Node.Null : (Node) base.value,
       baseStack = new NodeStack(baseStack),
       fork == null ? Node.Null : (Node) fork.value,
       forkStack = new NodeStack(forkStack),
       list(Interval.Unbounded).iterator(),
       false);

    if (pairs[depth] == null) {
      pairs[depth] = new DiffPair();
    }
  }

  public void ascend() {
    iterators[depth] = null;

    -- depth;

    baseStack = baseStack.popStack();
    forkStack = forkStack.popStack();
  }

  public Object fork() {
    return state.fork(this);
  }

  public Object base() {
    return state.base(this);
  }

  public void skip() {
    state.skip(this);
  }
}