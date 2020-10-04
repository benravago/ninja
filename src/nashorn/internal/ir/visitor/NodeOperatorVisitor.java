/*
 * Copyright (c) 2010, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package nashorn.internal.ir.visitor;

import nashorn.internal.ir.BinaryNode;
import nashorn.internal.ir.LexicalContext;
import nashorn.internal.ir.Node;
import nashorn.internal.ir.UnaryNode;

/**
 * Like NodeVisitor but navigating further into operators.
 *
 * @param <T> Lexical context class for this NodeOperatorVisitor
 */
public abstract class NodeOperatorVisitor<T extends LexicalContext> extends NodeVisitor<T> {

    /**
     * Constructor
     */
    public NodeOperatorVisitor(T lc) {
        super(lc);
    }

    @Override
    public boolean enterUnaryNode(UnaryNode unaryNode) {
        return switch (unaryNode.tokenType()) {
            case POS -> enterPOS(unaryNode);
            case BIT_NOT -> enterBIT_NOT(unaryNode);
            case DELETE -> enterDELETE(unaryNode);
            case NEW -> enterNEW(unaryNode);
            case NOT -> enterNOT(unaryNode);
            case NEG -> enterNEG(unaryNode);
            case TYPEOF -> enterTYPEOF(unaryNode);
            case VOID -> enterVOID(unaryNode);
            case DECPREFIX, DECPOSTFIX, INCPREFIX, INCPOSTFIX -> enterDECINC(unaryNode);
            default -> super.enterUnaryNode(unaryNode);
        };
    }

    @Override
    public final Node leaveUnaryNode(UnaryNode unaryNode) {
        return switch (unaryNode.tokenType()) {
            case POS -> leavePOS(unaryNode);
            case BIT_NOT -> leaveBIT_NOT(unaryNode);
            case DELETE -> leaveDELETE(unaryNode);
            case NEW -> leaveNEW(unaryNode);
            case NOT -> leaveNOT(unaryNode);
            case NEG -> leaveNEG(unaryNode);
            case TYPEOF -> leaveTYPEOF(unaryNode);
            case VOID -> leaveVOID(unaryNode);
            case DECPREFIX, DECPOSTFIX, INCPREFIX, INCPOSTFIX -> leaveDECINC(unaryNode);
            default -> super.leaveUnaryNode(unaryNode);
        };
    }

    @Override
    public final boolean enterBinaryNode(BinaryNode binaryNode) {
        return switch (binaryNode.tokenType()) {
            case ADD -> enterADD(binaryNode);
            case AND -> enterAND(binaryNode);
            case ASSIGN -> enterASSIGN(binaryNode);
            case ASSIGN_ADD -> enterASSIGN_ADD(binaryNode);
            case ASSIGN_BIT_AND -> enterASSIGN_BIT_AND(binaryNode);
            case ASSIGN_BIT_OR -> enterASSIGN_BIT_OR(binaryNode);
            case ASSIGN_BIT_XOR -> enterASSIGN_BIT_XOR(binaryNode);
            case ASSIGN_DIV -> enterASSIGN_DIV(binaryNode);
            case ASSIGN_MOD -> enterASSIGN_MOD(binaryNode);
            case ASSIGN_MUL -> enterASSIGN_MUL(binaryNode);
            case ASSIGN_SAR -> enterASSIGN_SAR(binaryNode);
            case ASSIGN_SHL -> enterASSIGN_SHL(binaryNode);
            case ASSIGN_SHR -> enterASSIGN_SHR(binaryNode);
            case ASSIGN_SUB -> enterASSIGN_SUB(binaryNode);
            case ARROW -> enterARROW(binaryNode);
            case BIT_AND -> enterBIT_AND(binaryNode);
            case BIT_OR -> enterBIT_OR(binaryNode);
            case BIT_XOR -> enterBIT_XOR(binaryNode);
            case COMMARIGHT -> enterCOMMARIGHT(binaryNode);
            case DIV -> enterDIV(binaryNode);
            case EQ -> enterEQ(binaryNode);
            case EQ_STRICT -> enterEQ_STRICT(binaryNode);
            case GE -> enterGE(binaryNode);
            case GT -> enterGT(binaryNode);
            case IN -> enterIN(binaryNode);
            case INSTANCEOF -> enterINSTANCEOF(binaryNode);
            case LE -> enterLE(binaryNode);
            case LT -> enterLT(binaryNode);
            case MOD -> enterMOD(binaryNode);
            case MUL -> enterMUL(binaryNode);
            case NE -> enterNE(binaryNode);
            case NE_STRICT -> enterNE_STRICT(binaryNode);
            case OR -> enterOR(binaryNode);
            case SAR -> enterSAR(binaryNode);
            case SHL -> enterSHL(binaryNode);
            case SHR -> enterSHR(binaryNode);
            case SUB -> enterSUB(binaryNode);
            default -> super.enterBinaryNode(binaryNode);
        };
    }

    @Override
    public final Node leaveBinaryNode(BinaryNode binaryNode) {
        return switch (binaryNode.tokenType()) {
            case ADD -> leaveADD(binaryNode);
            case AND -> leaveAND(binaryNode);
            case ASSIGN -> leaveASSIGN(binaryNode);
            case ASSIGN_ADD -> leaveASSIGN_ADD(binaryNode);
            case ASSIGN_BIT_AND -> leaveASSIGN_BIT_AND(binaryNode);
            case ASSIGN_BIT_OR -> leaveASSIGN_BIT_OR(binaryNode);
            case ASSIGN_BIT_XOR -> leaveASSIGN_BIT_XOR(binaryNode);
            case ASSIGN_DIV -> leaveASSIGN_DIV(binaryNode);
            case ASSIGN_MOD -> leaveASSIGN_MOD(binaryNode);
            case ASSIGN_MUL -> leaveASSIGN_MUL(binaryNode);
            case ASSIGN_SAR -> leaveASSIGN_SAR(binaryNode);
            case ASSIGN_SHL -> leaveASSIGN_SHL(binaryNode);
            case ASSIGN_SHR -> leaveASSIGN_SHR(binaryNode);
            case ASSIGN_SUB -> leaveASSIGN_SUB(binaryNode);
            case ARROW -> leaveARROW(binaryNode);
            case BIT_AND -> leaveBIT_AND(binaryNode);
            case BIT_OR -> leaveBIT_OR(binaryNode);
            case BIT_XOR -> leaveBIT_XOR(binaryNode);
            case COMMARIGHT -> leaveCOMMARIGHT(binaryNode);
            case DIV -> leaveDIV(binaryNode);
            case EQ -> leaveEQ(binaryNode);
            case EQ_STRICT -> leaveEQ_STRICT(binaryNode);
            case GE -> leaveGE(binaryNode);
            case GT -> leaveGT(binaryNode);
            case IN -> leaveIN(binaryNode);
            case INSTANCEOF -> leaveINSTANCEOF(binaryNode);
            case LE -> leaveLE(binaryNode);
            case LT -> leaveLT(binaryNode);
            case MOD -> leaveMOD(binaryNode);
            case MUL -> leaveMUL(binaryNode);
            case NE -> leaveNE(binaryNode);
            case NE_STRICT -> leaveNE_STRICT(binaryNode);
            case OR -> leaveOR(binaryNode);
            case SAR -> leaveSAR(binaryNode);
            case SHL -> leaveSHL(binaryNode);
            case SHR -> leaveSHR(binaryNode);
            case SUB -> leaveSUB(binaryNode);
            default -> super.leaveBinaryNode(binaryNode);
        };
    }

    /**
     * Unary entries and exists.
     */

    /** Unary enter - callback for entering a unary + operator */
    public boolean enterPOS(UnaryNode unaryNode) {
        return enterDefault(unaryNode);
    }

    /** Unary leave - callback for leaving a unary + operator */
     public Node leavePOS(UnaryNode unaryNode) {
        return leaveDefault(unaryNode);
    }

    /** Unary enter - callback for entering a ~ operator */
    public boolean enterBIT_NOT(UnaryNode unaryNode) {
        return enterDefault(unaryNode);
    }

    /** Unary leave - callback for leaving a unary ~ operator */
    public Node leaveBIT_NOT(UnaryNode unaryNode) {
        return leaveDefault(unaryNode);
    }

    /** Unary enter - callback for entering a ++ or -- operator */
    public boolean enterDECINC(UnaryNode unaryNode) {
        return enterDefault(unaryNode);
    }

    /** Unary leave - callback for leaving a ++ or -- operator */
     public Node leaveDECINC(UnaryNode unaryNode) {
        return leaveDefault(unaryNode);
    }

    /** Unary enter - callback for entering a delete operator */
    public boolean enterDELETE(UnaryNode unaryNode) {
        return enterDefault(unaryNode);
    }

    /** Unary leave - callback for leaving a delete operator */
     public Node leaveDELETE(UnaryNode unaryNode) {
        return leaveDefault(unaryNode);
    }

    /** Unary enter - callback for entering a new operator */
    public boolean enterNEW(UnaryNode unaryNode) {
        return enterDefault(unaryNode);
    }

    /** Unary leave - callback for leaving a new operator */
     public Node leaveNEW(UnaryNode unaryNode) {
        return leaveDefault(unaryNode);
    }

    /** Unary enter - callback for entering a ! operator */
    public boolean enterNOT(UnaryNode unaryNode) {
        return enterDefault(unaryNode);
    }

    /** Unary leave - callback for leaving a ! operator */
     public Node leaveNOT(UnaryNode unaryNode) {
        return leaveDefault(unaryNode);
    }

    /** Unary enter - callback for entering a unary - operator */
    public boolean enterNEG(UnaryNode unaryNode) {
        return enterDefault(unaryNode);
    }

    /** Unary leave - callback for leaving a unary - operator */
    public Node leaveNEG(UnaryNode unaryNode) {
        return leaveDefault(unaryNode);
    }

    /** Unary enter - callback for entering a typeof operator */
    public boolean enterTYPEOF(UnaryNode unaryNode) {
        return enterDefault(unaryNode);
    }

    /** Unary leave - callback for leaving a typeof operator */
     public Node leaveTYPEOF(UnaryNode unaryNode) {
        return leaveDefault(unaryNode);
    }

    /** Unary enter - callback for entering a void operator */
    public boolean enterVOID(UnaryNode unaryNode) {
        return enterDefault(unaryNode);
    }

    /** Unary leave - callback for leaving a void operator */
     public Node leaveVOID(UnaryNode unaryNode) {
        return leaveDefault(unaryNode);
    }

    /** Binary enter - callback for entering + operator */
    public boolean enterADD(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving a + operator */
     public Node leaveADD(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering {@literal &&} operator */
    public boolean enterAND(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving a {@literal &&} operator */
    public Node leaveAND(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering an assignment operator */
    public boolean enterASSIGN(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving an assignment operator */
    public Node leaveASSIGN(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering += operator */
    public boolean enterASSIGN_ADD(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving a += operator */
    public Node leaveASSIGN_ADD(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering {@literal &=} operator */
    public boolean enterASSIGN_BIT_AND(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving a {@literal &=} operator */
    public Node leaveASSIGN_BIT_AND(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering |= operator */
    public boolean enterASSIGN_BIT_OR(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving a |= operator */
    public Node leaveASSIGN_BIT_OR(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering ^= operator */
    public boolean enterASSIGN_BIT_XOR(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving a ^= operator */
    public Node leaveASSIGN_BIT_XOR(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering /= operator */
    public boolean enterASSIGN_DIV(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving a /= operator */
    public Node leaveASSIGN_DIV(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering %= operator */
    public boolean enterASSIGN_MOD(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving a %= operator */
    public Node leaveASSIGN_MOD(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering *= operator */
    public boolean enterASSIGN_MUL(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving a *= operator */
    public Node leaveASSIGN_MUL(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering {@literal >>=} operator */
    public boolean enterASSIGN_SAR(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving a {@literal >>=} operator */
    public Node leaveASSIGN_SAR(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering a {@literal <<=} operator */
    public boolean enterASSIGN_SHL(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving a {@literal <<=} operator */
    public Node leaveASSIGN_SHL(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering {@literal >>>=} operator */
    public boolean enterASSIGN_SHR(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving a {@literal >>>=} operator */
    public Node leaveASSIGN_SHR(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering -= operator */
    public boolean enterASSIGN_SUB(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving a -= operator */
    public Node leaveASSIGN_SUB(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering a arrow operator */
    public boolean enterARROW(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving a arrow operator */
    public Node leaveARROW(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering {@literal &} operator */
    public boolean enterBIT_AND(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving a {@literal &} operator */
    public Node leaveBIT_AND(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering | operator */
    public boolean enterBIT_OR(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving a | operator */
    public Node leaveBIT_OR(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering ^ operator */
    public boolean enterBIT_XOR(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving a  operator */
    public Node leaveBIT_XOR(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering comma right operator (a, b) where the result is b */
    public boolean enterCOMMARIGHT(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving a comma right operator (a, b) where the result is b */
    public Node leaveCOMMARIGHT(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering a division operator */
    public boolean enterDIV(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving a division operator */
    public Node leaveDIV(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering == operator */
    public boolean enterEQ(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving == operator */
    public Node leaveEQ(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering === operator */
    public boolean enterEQ_STRICT(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving === operator */
    public Node leaveEQ_STRICT(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering {@literal >=} operator */
    public boolean enterGE(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving {@literal >=} operator */
    public Node leaveGE(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering {@literal >} operator */
    public boolean enterGT(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving {@literal >} operator */
    public Node leaveGT(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering in operator */
    public boolean enterIN(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving in operator */
    public Node leaveIN(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering instanceof operator */
    public boolean enterINSTANCEOF(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving instanceof operator */
    public Node leaveINSTANCEOF(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering {@literal <=} operator */
    public boolean enterLE(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving {@literal <=} operator */
    public Node leaveLE(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering {@literal <} operator */
    public boolean enterLT(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving {@literal <} operator */
    public Node leaveLT(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }
    /** Binary enter - callback for entering % operator */
    public boolean enterMOD(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving % operator */
    public Node leaveMOD(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering * operator */
    public boolean enterMUL(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving * operator */
    public Node leaveMUL(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering != operator */
    public boolean enterNE(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving != operator */
    public Node leaveNE(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering a !== operator */
    public boolean enterNE_STRICT(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving !== operator */
    public Node leaveNE_STRICT(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering || operator */
    public boolean enterOR(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving || operator */
    public Node leaveOR(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering {@literal >>} operator */
    public boolean enterSAR(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving {@literal >>} operator */
    public Node leaveSAR(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering {@literal <<} operator */
    public boolean enterSHL(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving {@literal <<} operator */
    public Node leaveSHL(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }
    /** Binary enter - callback for entering {@literal >>>} operator */
    public boolean enterSHR(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving {@literal >>>} operator */
    public Node leaveSHR(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Binary enter - callback for entering - operator */
    public boolean enterSUB(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Binary leave - callback for leaving - operator */
    public Node leaveSUB(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

}
